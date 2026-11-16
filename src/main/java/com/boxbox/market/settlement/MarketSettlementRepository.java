package com.boxbox.market.settlement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketSettlementRepository {
    private final NamedParameterJdbcTemplate jdbc;

    List<Summary> find(String loginId, Integer season, Cursor cursor, int limit) {
        var sql = new StringBuilder("""
                SELECT m.id AS round_id, m.season_year, r.name AS race_name, r.round_number,
                       s.status, s.round_start_assets, s.ending_assets, s.round_pnl, s.return_rate,
                       s.round_rank, s.settlement_proceeds, s.published_at
                FROM user_settlements s
                JOIN users u ON u.id = s.user_id
                JOIN market_rounds m ON m.id = s.round_id
                JOIN races r ON r.id = m.race_id AND r.season_year = m.season_year
                WHERE u.login_id = :loginId AND u.deleted_at IS NULL AND u.status IN ('ACTIVE', 'SUSPENDED')
                  AND NOT EXISTS (
                      SELECT 1 FROM user_settlements newer
                      WHERE newer.user_id = s.user_id AND newer.round_id = s.round_id
                        AND (newer.settlement_version > s.settlement_version
                             OR (newer.settlement_version = s.settlement_version AND newer.id > s.id))
                  )
                """);
        var params = new MapSqlParameterSource("loginId", loginId).addValue("limit", limit);
        if (season != null) {
            sql.append(" AND m.season_year = :season");
            params.addValue("season", season);
        }
        if (cursor != null) {
            sql.append("""
                     AND (m.season_year < :cursorSeason OR (m.season_year = :cursorSeason
                          AND (r.round_number < :cursorRound OR (r.round_number = :cursorRound AND m.id < :cursorId))))
                    """);
            params.addValue("cursorSeason", cursor.season()).addValue("cursorRound", cursor.roundNumber())
                    .addValue("cursorId", new BigDecimal(cursor.roundId()));
        }
        sql.append(" ORDER BY m.season_year DESC, r.round_number DESC, m.id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (row, index) -> {
            var publishedAt = row.getObject("published_at", LocalDateTime.class);
            return new Summary(row.getString("round_id"), row.getInt("season_year"), row.getString("race_name"),
                    row.getInt("round_number"), row.getString("status"), integer(row, "round_start_assets"),
                    integer(row, "ending_assets"), integer(row, "round_pnl"), row.getBigDecimal("return_rate"),
                    row.getObject("round_rank", Long.class), integer(row, "settlement_proceeds"),
                    publishedAt == null ? null : publishedAt.toInstant(ZoneOffset.UTC));
        });
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        var value = row.getBigDecimal(column);
        return value == null ? null : value.toBigIntegerExact();
    }

    record Cursor(int season, int roundNumber, BigInteger roundId) { }

    record Summary(String roundId, int season, String raceName, int roundNumber, String status,
                   BigInteger roundStartAssets, BigInteger endingAssets, BigInteger roundPnl, BigDecimal returnRate,
                   Long rank, BigInteger settlementProceeds, Instant publishedAt) { }
}
