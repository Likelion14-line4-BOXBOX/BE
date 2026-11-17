package com.boxbox.market.preparation;

import com.boxbox.openf1.OpenF1Client;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketRoundPreparationService {
    private final MarketRaceScheduleService schedule;
    private final OpenF1Client openF1;
    private final MarketRoundPreparationRepository repository;

    public Optional<String> prepare(int season, int roundNumber, Instant firstRoundOpenAt) {
        var races = schedule.findMatchedRaces(season);
        var race = races.stream().filter(candidate -> candidate.roundNumber() == roundNumber).findFirst();
        if (race.isEmpty()) {
            return Optional.empty();
        }

        var target = race.get();
        var window = schedule.tradingWindow(target, races, firstRoundOpenAt);
        if (window.isEmpty()) {
            return Optional.empty();
        }
        var roster = new HashSet<Integer>();
        for (var driver : openF1.getSessionDrivers(target.sessionKey())) {
            if (driver.driverNumber() < 1 || !roster.add(driver.driverNumber())) {
                return Optional.empty();
            }
        }
        if (roster.isEmpty()) {
            return Optional.empty();
        }
        if (roundNumber == 1) {
            return repository.save(season, window.get(), target.settlementPublishAt().orElse(null), roster, Map.of());
        }

        var prior = races.stream()
                .filter(candidate -> candidate.roundNumber() == roundNumber - 1)
                .findFirst().orElseThrow();
        var results = openF1.getSessionResults(prior.sessionKey());
        Map<Integer, Integer> ranks = new HashMap<>();
        var usedRanks = new HashSet<Integer>();
        var resultDrivers = new HashSet<Integer>();
        for (var result : results) {
            if (result.driverNumber() == null || !resultDrivers.add(result.driverNumber())) {
                return Optional.empty();
            }
            Integer rank = result.position();
            if (rank == null && (Boolean.TRUE.equals(result.dnf()) || Boolean.TRUE.equals(result.dns())
                    || Boolean.TRUE.equals(result.dsq()))) {
                continue;
            }
            if (rank == null || MarketStartingPrices.forRank(rank).isEmpty()
                    || !usedRanks.add(rank) || ranks.putIfAbsent(result.driverNumber(), rank) != null) {
                return Optional.empty();
            }
        }
        if (ranks.isEmpty()) {
            return Optional.empty();
        }
        return repository.save(season, window.get(), target.settlementPublishAt().orElse(null), roster, ranks);
    }
}
