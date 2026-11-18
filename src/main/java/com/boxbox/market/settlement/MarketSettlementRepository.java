package com.boxbox.market.settlement;

import com.boxbox.market.settlement.dto.MarketSettlementDetailResponse.Item;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
@RequiredArgsConstructor
class MarketSettlementRepository {
    private final NamedParameterJdbcTemplate jdbc;

    List<Summary> find(String loginId, Integer season, Cursor cursor, int limit) {
        var sql = new StringBuilder("""
                SELECT m.id AS round_id, m.season_year, COALESCE(NULLIF(TRIM(r.name_ko),''),r.name) AS race_name, r.round_number,
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

    Optional<Detail> findDetail(String loginId, BigInteger roundId) {
        return jdbc.query("""
                SELECT u.id AS user_id, s.id AS settlement_id, s.round_id, s.status, s.settlement_version,
                       m.rule_version, s.rule_snapshot, s.round_start_assets, s.ending_assets, s.round_pnl,
                       s.return_rate, s.round_rank, s.season_rank, s.season_return_rate,
                       s.settlement_proceeds, s.published_at
                FROM user_settlements s
                JOIN users u ON u.id = s.user_id
                JOIN market_rounds m ON m.id = s.round_id
                WHERE u.login_id = :loginId AND u.deleted_at IS NULL AND u.status IN ('ACTIVE', 'SUSPENDED')
                  AND s.round_id = :roundId
                ORDER BY s.settlement_version DESC, s.id DESC LIMIT 1
                """, new MapSqlParameterSource("loginId", loginId).addValue("roundId", new BigDecimal(roundId)),
                (row, index) -> {
                    var published = row.getObject("published_at", LocalDateTime.class);
                    return new Detail(integer(row, "user_id"), integer(row, "settlement_id"),
                            row.getString("round_id"), row.getString("status"), row.getLong("settlement_version"),
                            row.getString("rule_version"), row.getString("rule_snapshot"),
                            integer(row, "round_start_assets"), integer(row, "ending_assets"), integer(row, "round_pnl"),
                            row.getBigDecimal("return_rate"), row.getObject("round_rank", Long.class),
                            row.getObject("season_rank", Long.class), row.getBigDecimal("season_return_rate"),
                            integer(row, "settlement_proceeds"), published == null ? null : published.toInstant(ZoneOffset.UTC));
                }).stream().findFirst();
    }

    List<Item> findItems(Detail detail, JsonNode driverNames) {
        return jdbc.query("""
                SELECT s.driver_id, d.name AS driver_name, NULLIF(TRIM(d.name_ko),'') AS driver_name_ko, i.base_rank, i.finish_rank,
                       i.position_score, i.result_bonus, i.final_score, i.starting_price,
                       i.settlement_price, i.quantity, i.total_cost, i.proceeds, i.pnl
                FROM user_settlement_items i
                JOIN market_stocks s ON s.id = i.stock_id AND s.round_id = i.round_id
                JOIN drivers d ON d.id = s.driver_id
                WHERE i.user_settlement_id = :settlementId AND i.user_id = :userId
                  AND i.settlement_version = :version AND i.round_id = :roundId
                ORDER BY i.base_rank, i.stock_id
                """, new MapSqlParameterSource("settlementId", new BigDecimal(detail.settlementId()))
                .addValue("userId", new BigDecimal(detail.userId())).addValue("version", detail.version())
                .addValue("roundId", new BigDecimal(detail.roundId())),
                (row, index) -> {
                    String driverId = row.getString("driver_id");
                    // New settlements freeze names by driver ID in rule_snapshot.driverNames.
                    JsonNode frozenName = driverNames.path(driverId);
                    String name = frozenName.isString() && !frozenName.stringValue().isBlank()
                            ? frozenName.stringValue() : row.getString("driver_name");
                    return new Item(driverId, row.getString("driver_name_ko") == null ? name : row.getString("driver_name_ko"), row.getInt("base_rank"),
                            row.getObject("finish_rank", Integer.class), row.getInt("position_score"),
                            row.getInt("result_bonus"), row.getInt("final_score"), integer(row, "starting_price"),
                            integer(row, "settlement_price"), integer(row, "quantity"), integer(row, "total_cost"),
                            integer(row, "proceeds"), integer(row, "pnl"));
                });
    }

    record Detail(BigInteger userId, BigInteger settlementId, String roundId, String status, long version,
                  String ruleVersion, String ruleSnapshot, BigInteger roundStartAssets, BigInteger endingAssets,
                  BigInteger roundPnl, BigDecimal returnRate, Long rank, Long seasonRank, BigDecimal seasonReturnRate,
                  BigInteger settlementProceeds, Instant publishedAt) { }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        var value = row.getBigDecimal(column);
        return value == null ? null : value.toBigIntegerExact();
    }

    record Cursor(int season, int roundNumber, BigInteger roundId) { }

    record Summary(String roundId, int season, String raceName, int roundNumber, String status,
                   BigInteger roundStartAssets, BigInteger endingAssets, BigInteger roundPnl, BigDecimal returnRate,
                   Long rank, BigInteger settlementProceeds, Instant publishedAt) { }
}
