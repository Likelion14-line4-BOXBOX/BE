package com.boxbox.home;

import com.boxbox.common.api.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class HomeRepository {
    private final JdbcClient jdbc;

    HomeResponse.Race nextRace(int season, Instant now) {
        return jdbc.sql("""
                SELECT id, season_year, round_number, name, country_name, circuit_name, circuit_key, status,
                       scheduled_start_at, actual_end_at, weekend_starts_at, weekend_ends_at
                FROM races
                WHERE season_year = ? AND status IN ('SCHEDULED', 'LIVE')
                  AND (status = 'LIVE' OR weekend_ends_at > ?) AND actual_end_at IS NULL
                ORDER BY CASE WHEN status = 'LIVE' THEN 0 ELSE 1 END, weekend_starts_at, id
                LIMIT 1
                """).params(season, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((row, index) -> new HomeResponse.Race(row.getString("id"), row.getInt("season_year"),
                        row.getInt("round_number"), row.getString("name"), row.getString("country_name"),
                        row.getString("circuit_name"), row.getString("circuit_key"), instant(row, "scheduled_start_at"),
                        instant(row, "actual_end_at"), row.getString("status"), instant(row, "weekend_starts_at"),
                        instant(row, "weekend_ends_at"))).optional().orElse(null);
    }

    HomeResponse.Session nextSession(String raceId, Instant now) {
        return jdbc.sql("""
                SELECT id, name, starts_at FROM race_sessions
                WHERE race_id = ? AND status = 'SCHEDULED' AND starts_at >= ? AND actual_starts_at IS NULL
                ORDER BY starts_at, id LIMIT 1
                """).params(raceId, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((row, index) -> new HomeResponse.Session(
                        row.getString("id"), row.getString("name"), instant(row, "starts_at")))
                .optional().orElse(null);
    }

    List<Featured> latestPosts() {
        return jdbc.sql("""
                SELECT id, board_name, board_type FROM (
                    SELECT p.id, b.name AS board_name, b.type AS board_type, p.created_at,
                           ROW_NUMBER() OVER (PARTITION BY p.board_id ORDER BY p.created_at DESC, p.id DESC) AS position
                    FROM posts p JOIN boards b ON b.id = p.board_id
                    WHERE p.deleted_at IS NULL AND b.is_active = TRUE
                ) latest
                WHERE position = 1
                ORDER BY created_at DESC, id DESC
                """).query((row, index) -> new Featured(
                        row.getLong("id"), row.getString("board_name"), row.getString("board_type"))).list();
    }

    Long viewer(String loginId) {
        return jdbc.sql("SELECT id FROM users WHERE login_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL")
                .param(loginId).query(Long.class).optional().orElseThrow(ApiException::unauthorized);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    record Featured(long id, String boardName, String boardType) {}
}
