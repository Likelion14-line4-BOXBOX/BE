package com.boxbox.race;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
class RaceScheduleService {
    private final JdbcClient jdbc;

    RacePage getRaces(int season, RaceStatus filter, int afterRound, int size) {
        String sql = """
                SELECT id, season_year, round_number, COALESCE(NULLIF(TRIM(name_ko),''),name) AS name, name AS name_en, country_name, circuit_name, circuit_key,
                       scheduled_start_at, actual_end_at, status
                FROM races
                WHERE season_year = :season AND round_number > :afterRound
                """;
        if (filter != null) {
            sql += " AND status = :status";
        }
        var query = jdbc.sql(sql + " ORDER BY round_number, id LIMIT :limit")
                .param("season", season)
                .param("afterRound", afterRound)
                .param("limit", size + 1);
        if (filter != null) {
            query.param("status", filter.name());
        }

        List<RaceItem> items = query.query((row, rowNumber) -> new RaceItem(
                row.getString("id"), row.getInt("season_year"), row.getInt("round_number"),
                row.getString("name"), row.getString("name_en"), row.getString("country_name"), row.getString("circuit_name"),
                row.getString("circuit_key"), null, instant(row, "scheduled_start_at"),
                instant(row, "actual_end_at"), RaceStatus.valueOf(row.getString("status"))))
                .list();
        boolean hasNext = items.size() > size;
        List<RaceItem> page = List.copyOf(items.subList(0, Math.min(size, items.size())));
        String nextCursor = hasNext ? cursorPrefix(season, filter) + page.getLast().roundNumber() : null;
        return new RacePage(page, nextCursor, hasNext);
    }

    static String cursorPrefix(int season, RaceStatus filter) {
        return season + "-" + (filter == null ? "ALL" : filter.name()) + "-";
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    enum RaceStatus { SCHEDULED, LIVE, FINISHED, CANCELLED }

    record RacePage(List<RaceItem> items, String nextCursor, boolean hasNext) {}

    record RaceItem(String raceId, int season, int roundNumber, String name, String nameEn, String countryName,
                    String circuitName, String circuitKey, String circuitImageUrl, Instant scheduledStartAt,
                    Instant actualEndAt, RaceStatus status) {}
}
