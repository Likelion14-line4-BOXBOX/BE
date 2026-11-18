package com.boxbox.market.preparation;

import com.boxbox.race.RaceSessionSyncService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "boxbox.market.preparation.enabled", havingValue = "true")
class MarketRoundPreparationJob {
    private final MarketRoundPreparationService preparation;
    private final MarketRoundPreparationRepository repository;
    private final Clock clock;
    private final RaceSessionSyncService sessions;
    private final boolean sessionSyncEnabled;
    private final int season;
    private final Instant firstRoundOpenAt;

    MarketRoundPreparationJob(MarketRoundPreparationService preparation, MarketRoundPreparationRepository repository,
                              RaceSessionSyncService sessions, Clock clock,
                              @Value("${boxbox.race.session-sync.enabled:true}") boolean sessionSyncEnabled,
                              @Value("${boxbox.market.preparation.season}") int season,
                              @Value("${boxbox.market.preparation.first-round-open-at:}") String firstRoundOpenAt) {
        this.preparation = preparation;
        this.repository = repository;
        this.clock = clock;
        this.sessions = sessions;
        this.sessionSyncEnabled = sessionSyncEnabled;
        this.season = season;
        this.firstRoundOpenAt = firstRoundOpenAt.isBlank() ? null : Instant.parse(firstRoundOpenAt);
    }

    @Bean
    ThreadPoolTaskScheduler marketPreparationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("market-preparation-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${boxbox.market.preparation.status-interval:PT30S}",
            scheduler = "marketPreparationScheduler")
    void updateRounds() {
        var now = clock.instant();
        repository.advanceRounds(season, now);
        repository.fillPublicationTimes(season, now);
    }

    @Scheduled(fixedDelayString = "${boxbox.race.session-sync.interval:PT1H}", scheduler = "marketPreparationScheduler")
    void syncRaceSessions() {
        if (sessionSyncEnabled) {
            sessions.sync(season);
        }
    }

    @Scheduled(fixedDelayString = "${boxbox.market.preparation.interval:PT15M}", scheduler = "marketPreparationScheduler")
    void prepareNextRound() {
        repository.nextUnpreparedRound(season, clock.instant())
                .ifPresent(round -> preparation.prepare(season, round, firstRoundOpenAt));
    }
}
