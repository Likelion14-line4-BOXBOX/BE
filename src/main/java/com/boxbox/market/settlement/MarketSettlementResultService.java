package com.boxbox.market.settlement;

import com.boxbox.market.preparation.MarketRaceScheduleService;
import com.boxbox.market.round.MarketRoundRepository;
import com.boxbox.openf1.OpenF1Client;
import com.boxbox.openf1.OpenF1Client.SessionResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketSettlementResultService {
    private final MarketRoundRepository rounds;
    private final MarketRaceScheduleService schedule;
    private final OpenF1Client openF1;
    private final NamedParameterJdbcTemplate jdbc;

    public Optional<MatchedResult> load(BigInteger roundId, Instant now) {
        var detail = rounds.findById(roundId);
        if (detail.isEmpty() || !"CLOSED".equals(detail.get().round().status())) {
            return Optional.empty();
        }
        var round = detail.get().round();
        if (round.closeAt() == null || now.isBefore(round.closeAt())) {
            return Optional.empty();
        }

        var race = schedule.findMatchedRaces(round.season()).stream()
                .filter(candidate -> candidate.raceId().equals(round.raceId()))
                .filter(candidate -> candidate.actualEndAt() != null && !now.isBefore(candidate.actualEndAt()))
                .findFirst();
        if (race.isEmpty()) {
            return Optional.empty();
        }

        var stocks = jdbc.query("""
                SELECT s.id, s.driver_id, d.name, d.car_number, s.base_rank, s.starting_price
                FROM market_stocks s JOIN drivers d ON d.id = s.driver_id
                WHERE s.round_id = :round AND s.season_year = :season
                ORDER BY s.base_rank, s.id
                """, Map.of("round", new BigDecimal(roundId), "season", round.season()),
                (row, index) -> new Stock(row.getBigDecimal("id").toBigIntegerExact(),
                        row.getString("driver_id"), row.getString("name"),
                        row.getObject("car_number", Integer.class), row.getInt("base_rank"),
                        row.getBigDecimal("starting_price").toBigIntegerExact()));
        if (stocks.isEmpty()) {
            return Optional.empty();
        }

        var session = race.get();
        return match(stocks, openF1.getSessionResults(session.sessionKey()))
                .map(items -> new MatchedResult(roundId, session.sessionKey(),
                        session.settlementPublishAt().orElseThrow(), detail.get().ruleSnapshot(), items));
    }

    static Optional<List<DriverResult>> match(List<Stock> stocks, List<SessionResult> results) {
        Map<Integer, SessionResult> byNumber = new HashMap<>();
        var usedRanks = new HashSet<Integer>();
        for (var result : results) {
            if (result.driverNumber() == null || byNumber.putIfAbsent(result.driverNumber(), result) != null) {
                return Optional.empty();
            }
            if (result.position() != null && (result.position() < 1 || !usedRanks.add(result.position()))) {
                return Optional.empty();
            }
        }

        var matchedNumbers = new HashSet<Integer>();
        var items = new java.util.ArrayList<DriverResult>();
        for (var stock : stocks) {
            var result = byNumber.get(stock.number());
            if (result == null || !matchedNumbers.add(stock.number())
                    || Boolean.TRUE.equals(result.dns()) || Boolean.TRUE.equals(result.dsq())) {
                return Optional.empty();
            }
            boolean retired = Boolean.TRUE.equals(result.dnf());
            if (!retired && result.position() == null) {
                return Optional.empty();
            }
            items.add(new DriverResult(stock.id(), stock.driverId(), stock.driverName(), stock.baseRank(),
                    stock.startingPrice(), result.position(), retired));
        }
        return Optional.of(List.copyOf(items));
    }

    record Stock(BigInteger id, String driverId, String driverName, Integer number, int baseRank,
                 BigInteger startingPrice) {}

    public record DriverResult(BigInteger stockId, String driverId, String driverName, int baseRank,
                               BigInteger startingPrice, Integer finishRank, boolean retired) {
        public MarketSettlementPrice settlementPrice(int resultBonus) {
            return retired ? MarketSettlementPrice.retirement(startingPrice)
                    : MarketSettlementPrice.calculate(startingPrice, baseRank, finishRank, resultBonus);
        }
    }

    public record MatchedResult(BigInteger roundId, long sessionKey, Instant publishAt, String ruleSnapshot,
                                List<DriverResult> items) {}
}
