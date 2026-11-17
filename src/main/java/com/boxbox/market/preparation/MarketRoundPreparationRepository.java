package com.boxbox.market.preparation;

import com.boxbox.market.event.MarketEventLog;
import com.boxbox.market.preparation.MarketRaceScheduleService.TradingWindow;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class MarketRoundPreparationRepository {
    private static final SecureRandom IDS = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public void advanceRounds(int season, Instant now) {
        var when = utc(now);
        var parameters = Map.of("season", season, "now", when);
        var transitions = jdbc.query("""
                SELECT m.id, CASE WHEN m.close_at <= :now THEN 'CLOSED' ELSE 'OPEN' END AS next_status
                FROM market_rounds m JOIN races r ON r.id = m.race_id AND r.season_year = m.season_year
                WHERE m.season_year = :season AND r.status <> 'CANCELLED'
                  AND ((m.status IN ('SCHEDULED', 'OPEN') AND m.close_at <= :now)
                    OR (m.status = 'SCHEDULED' AND m.open_at <= :now AND m.close_at > :now))
                ORDER BY m.id FOR UPDATE
                """, parameters, (row, index) -> new Transition(row.getBigDecimal("id"), row.getString("next_status")));
        var template = jdbc.getJdbcTemplate();
        for (var transition : transitions) {
            template.update("""
                    UPDATE market_rounds SET status = ?, status_version = status_version + 1, updated_at = ?
                    WHERE id = ?
                    """, transition.status(), when, transition.id());
            var version = template.queryForObject("SELECT status_version FROM market_rounds WHERE id = ?",
                    BigDecimal.class, transition.id());
            MarketEventLog.append(template, transition.id(), "ROUND_STATUS_UPDATED", Map.of(
                    "roundId", transition.id().toPlainString(), "status", transition.status(),
                    "statusVersion", version), when);
        }
    }

    private record Transition(BigDecimal id, String status) {}

    Optional<Integer> nextUnpreparedRound(int season, Instant now) {
        return jdbc.query("""
                SELECT r.round_number
                FROM races r
                WHERE r.season_year = :season AND r.status <> 'CANCELLED'
                  AND r.scheduled_start_at > :earliestStart AND r.external_key IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM market_rounds m WHERE m.race_id = r.id)
                ORDER BY r.scheduled_start_at, r.id
                LIMIT 1
                """, Map.of("season", season, "earliestStart", utc(now.plusSeconds(3600))),
                (row, index) -> row.getInt(1)).stream().findFirst();
    }

    void fillPublicationTimes(int season, Instant now) {
        jdbc.update("""
                UPDATE market_rounds m
                JOIN races r ON r.id = m.race_id AND r.season_year = m.season_year
                SET m.publish_at = DATE_ADD(r.actual_end_at, INTERVAL 24 HOUR), m.updated_at = :now
                WHERE m.season_year = :season AND m.publish_at IS NULL AND r.actual_end_at IS NOT NULL
                  AND m.status <> 'CANCELLED' AND r.status <> 'CANCELLED'
                """, Map.of("season", season, "now", utc(now)));
    }

    @Transactional
    public Optional<String> save(int season, TradingWindow window, Instant publishAt, Set<Integer> roster,
                                 Map<Integer, Integer> ranks) {
        var race = Map.of("race", new BigDecimal(window.raceId()), "season", season);
        var locked = jdbc.query("""
                SELECT round_number FROM races
                WHERE id = :race AND season_year = :season AND status <> 'CANCELLED' FOR UPDATE
                """, race, (row, index) -> row.getInt(1));
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        var existing = jdbc.query("SELECT id FROM market_rounds WHERE race_id = :race FOR UPDATE", race,
                (row, index) -> row.getString(1)).stream().findFirst();
        if (existing.isPresent()) {
            if (publishAt != null) {
                jdbc.update("""
                        UPDATE market_rounds SET publish_at = :publish
                        WHERE id = :id AND publish_at IS NULL
                        """, Map.of("publish", utc(publishAt), "id", new BigDecimal(existing.get())));
            }
            return existing;
        }

        boolean firstRound = locked.getFirst() == 1;
        var drivers = jdbc.query("""
                SELECT id, team_id, car_number,
                       CASE WHEN stats_season_year = :priorSeason THEN championship_rank END AS prior_rank
                FROM drivers
                WHERE team_id IS NOT NULL AND car_number IN (:roster)
                ORDER BY id
                """, Map.of("priorSeason", season - 1, "roster", roster), (row, index) -> new Driver(
                row.getBigDecimal("id"), row.getBigDecimal("team_id"), row.getInt("car_number"),
                row.getObject("prior_rank", Integer.class)));
        var numbers = new HashSet<Integer>();
        var usedRanks = new HashSet<Integer>();
        var stocks = new java.util.ArrayList<Stock>();
        for (Driver driver : drivers) {
            if (!numbers.add(driver.number())) {
                return Optional.empty();
            }
            Integer rank = firstRound ? driver.priorRank() : ranks.get(driver.number());
            if (rank != null && !usedRanks.add(rank)) {
                return Optional.empty();
            }
            stocks.add(new Stock(driver, rank));
        }
        if (stocks.isEmpty() || usedRanks.isEmpty() || !numbers.equals(roster)) {
            return Optional.empty();
        }

        int nextRank = usedRanks.stream().mapToInt(Integer::intValue).max().orElseThrow() + 1;
        long roundId = nextId();
        var rows = new java.util.ArrayList<MapSqlParameterSource>();
        for (Stock stock : stocks) {
            int rank = stock.rank() == null ? nextRank++ : stock.rank();
            var price = MarketStartingPrices.forRank(rank);
            if (price.isEmpty()) {
                return Optional.empty();
            }
            rows.add(new MapSqlParameterSource()
                    .addValue("id", nextId()).addValue("round", roundId).addValue("season", season)
                    .addValue("driver", stock.driver().id()).addValue("team", stock.driver().teamId())
                    .addValue("rank", rank).addValue("price", price.getAsInt()));
        }

        jdbc.update("""
                INSERT INTO market_rounds
                    (id, race_id, season_year, status, open_at, close_at, publish_at, rule_version, rule_snapshot)
                VALUES (:id, :race, :season, 'SCHEDULED', :open, :close, :publish, 'rule-1', '{}')
                """, new MapSqlParameterSource()
                .addValue("id", roundId).addValue("race", race.get("race")).addValue("season", season)
                .addValue("open", utc(window.openAt())).addValue("close", utc(window.closeAt()))
                .addValue("publish", publishAt == null ? null : utc(publishAt)));
        jdbc.batchUpdate("""
                INSERT INTO market_stocks
                    (id, round_id, season_year, driver_id, team_id, base_rank, starting_price, current_price, theoretical_price)
                VALUES (:id, :round, :season, :driver, :team, :rank, :price, :price, :price)
                """, rows.toArray(MapSqlParameterSource[]::new));
        return Optional.of(Long.toString(roundId));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long nextId() {
        return IDS.nextLong(1, Long.MAX_VALUE);
    }

    private record Driver(BigDecimal id, BigDecimal teamId, int number, Integer priorRank) {}

    private record Stock(Driver driver, Integer rank) {}
}
