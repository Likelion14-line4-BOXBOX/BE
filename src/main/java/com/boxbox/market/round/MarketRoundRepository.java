package com.boxbox.market.round;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MarketRoundRepository {
    private static final String ROUND_COLUMNS = """
                m.id, m.race_id, m.season_year, r.round_number, r.name AS race_name,
                m.status, m.open_at, m.close_at, m.publish_at, m.ranking_published_at,
                m.rule_version, m.status_version, c.id AS chat_room_id
            """;
    private static final String ROUND_TABLES = """
            FROM market_rounds m
            JOIN races r ON r.id = m.race_id AND r.season_year = m.season_year
            LEFT JOIN chat_rooms c ON c.race_id = r.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    Optional<RoundRow> current(int season, Instant now) {
        return jdbc.query("SELECT " + ROUND_COLUMNS + ROUND_TABLES + """
                WHERE m.season_year = :season
                  AND m.status <> 'CANCELLED' AND r.status <> 'CANCELLED'
                  AND m.open_at IS NOT NULL AND m.publish_at IS NOT NULL
                ORDER BY CASE
                             WHEN m.status = 'OPEN' AND m.open_at <= :now AND m.close_at > :now THEN 0
                             WHEN m.status IN ('SCHEDULED', 'OPEN') AND m.open_at > :now THEN 1
                             ELSE 2
                         END,
                         CASE WHEN m.open_at > :now THEN m.open_at END,
                         m.open_at DESC, m.id DESC
                LIMIT 1
                """, Map.of("season", season, "now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
                (row, index) -> mapRound(row))
                .stream().findFirst();
    }

    public Optional<DetailRow> findById(BigInteger roundId) {
        return jdbc.query("SELECT " + ROUND_COLUMNS + ", m.rule_snapshot, m.settlement_status " + ROUND_TABLES
                        + "WHERE m.id = :roundId",
                Map.of("roundId", new BigDecimal(roundId)),
                (row, index) -> new DetailRow(mapRound(row), row.getString("rule_snapshot"),
                        row.getString("settlement_status")))
                .stream().findFirst();
    }

    private static RoundRow mapRound(ResultSet row) throws SQLException {
        return new RoundRow(row.getString("id"), row.getString("race_id"),
                row.getInt("season_year"), row.getInt("round_number"), row.getString("race_name"),
                row.getString("status"), instant(row, "open_at"), instant(row, "close_at"),
                instant(row, "publish_at"), instant(row, "ranking_published_at"),
                row.getString("rule_version"), row.getBigDecimal("status_version").toBigIntegerExact(),
                row.getString("chat_room_id"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime date = row.getObject(column, LocalDateTime.class);
        return date == null ? null : date.toInstant(ZoneOffset.UTC);
    }

    public record DetailRow(RoundRow round, String ruleSnapshot, String settlementStatus) { }

    public record RoundRow(String id, String raceId, int season, int roundNumber, String raceName, String status,
                    Instant openAt, Instant closeAt, Instant publishAt, Instant rankingPublishedAt,
                    String ruleVersion, BigInteger statusVersion, String chatRoomId) { }
}
