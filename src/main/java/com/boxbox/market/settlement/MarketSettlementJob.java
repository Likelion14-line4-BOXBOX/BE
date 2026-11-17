package com.boxbox.market.settlement;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "boxbox.market.settlement.enabled", havingValue = "true")
class MarketSettlementJob {
    private static final Logger log = LoggerFactory.getLogger(MarketSettlementJob.class);

    private final JdbcTemplate jdbc;
    private final MarketSettlementResultService results;
    private final MarketSettlementExecution execution;
    private final Clock clock;
    private final int season;

    MarketSettlementJob(JdbcTemplate jdbc, MarketSettlementResultService results,
                        MarketSettlementExecution execution, Clock clock,
                        @Value("${boxbox.market.preparation.season}") int season) {
        this.jdbc = jdbc;
        this.results = results;
        this.execution = execution;
        this.clock = clock;
        this.season = season;
    }

    @Bean
    ThreadPoolTaskScheduler marketSettlementScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("market-settlement-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${boxbox.market.settlement.interval:PT5M}", scheduler = "marketSettlementScheduler")
    void settleDueRounds() {
        var now = clock.instant();
        var roundIds = jdbc.queryForList("""
                SELECT id FROM market_rounds
                WHERE season_year = ? AND status = 'CLOSED' AND settlement_status = 'NOT_STARTED'
                  AND close_at <= ? AND publish_at <= ?
                ORDER BY publish_at, id
                """, BigDecimal.class, season, LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        for (var id : roundIds) {
            try {
                results.load(id.toBigIntegerExact(), now).ifPresent(result -> execution.execute(result, now));
            } catch (RuntimeException failure) {
                log.warn("마켓 라운드 {} 정산 실패. 다음 실행에서 다시 확인합니다.", id, failure);
            }
        }
    }
}
