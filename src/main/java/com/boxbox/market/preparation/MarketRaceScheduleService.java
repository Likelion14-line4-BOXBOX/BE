package com.boxbox.market.preparation;

import com.boxbox.openf1.OpenF1Client;
import com.boxbox.openf1.OpenF1Client.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketRaceScheduleService {
    private static final Duration OPEN_DELAY = Duration.ofHours(24);
    private static final Duration CLOSE_LEAD = Duration.ofHours(1);
    private static final Duration PUBLICATION_DELAY = Duration.ofHours(24);

    private final OpenF1Client openF1Client;
    private final MarketRaceRepository raceRepository;

    public List<MatchedRace> findMatchedRaces(int season) {
        Map<String, List<Session>> sessionsByMeeting = openF1Client.getSessions(season).stream()
                .filter(Session::isMainRace)
                .filter(session -> session.meetingKey() != null && session.sessionKey() != null)
                .collect(Collectors.groupingBy(session -> session.meetingKey().toString()));

        return raceRepository.findMappedRaces(season).stream()
                .filter(race -> sessionsByMeeting.getOrDefault(race.meetingKey(), List.of()).size() == 1)
                .map(race -> new MatchedRace(
                        race.id(), race.roundNumber(), sessionsByMeeting.get(race.meetingKey()).getFirst().sessionKey(),
                        race.scheduledStartAt(), race.actualEndAt()))
                .toList();
    }

    public Optional<TradingWindow> findTradingWindow(int season, int roundNumber, Instant firstRoundOpenAt) {
        List<MatchedRace> races = findMatchedRaces(season);
        return races.stream()
                .filter(race -> race.roundNumber() == roundNumber)
                .findFirst()
                .flatMap(race -> tradingWindow(race, races, firstRoundOpenAt));
    }

    Optional<TradingWindow> tradingWindow(
            MatchedRace race, List<MatchedRace> races, Instant firstRoundOpenAt
    ) {
        if (race.scheduledStartAt() == null) {
            return Optional.empty();
        }

        Instant openAt = firstRoundOpenAt;
        if (race.roundNumber() != 1) {
            openAt = races.stream()
                    .filter(previous -> previous.roundNumber() == race.roundNumber() - 1)
                    .findFirst()
                    .map(MatchedRace::actualEndAt)
                    .map(end -> end.plus(OPEN_DELAY))
                    .orElse(null);
        }

        Instant closeAt = race.scheduledStartAt().minus(CLOSE_LEAD);
        return openAt != null && openAt.isBefore(closeAt)
                ? Optional.of(new TradingWindow(race.raceId(), openAt, closeAt))
                : Optional.empty();
    }

    public record TradingWindow(String raceId, Instant openAt, Instant closeAt) {}

    public record MatchedRace(
            String raceId, int roundNumber, long sessionKey, Instant scheduledStartAt, Instant actualEndAt
    ) {
        public Optional<Instant> settlementPublishAt() {
            return Optional.ofNullable(actualEndAt).map(end -> end.plus(PUBLICATION_DELAY));
        }
    }
}
