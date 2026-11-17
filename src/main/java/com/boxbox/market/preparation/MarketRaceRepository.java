package com.boxbox.market.preparation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketRaceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    List<Race> findMappedRaces(int season) {
        return jdbc.query("""
                SELECT id, external_key, round_number, scheduled_start_at, actual_end_at
                FROM races
                WHERE season_year = :season AND external_key IS NOT NULL AND status <> 'CANCELLED'
                ORDER BY round_number, id
                """, Map.of("season", season), (row, index) -> new Race(
                row.getString("id"),
                row.getString("external_key"),
                row.getInt("round_number"),
                utc(row.getObject("scheduled_start_at", LocalDateTime.class)),
                utc(row.getObject("actual_end_at", LocalDateTime.class))));
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    record Race(String id, String meetingKey, int roundNumber, Instant scheduledStartAt, Instant actualEndAt) {}
}
