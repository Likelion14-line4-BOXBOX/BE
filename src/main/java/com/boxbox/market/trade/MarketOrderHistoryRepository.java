package com.boxbox.market.trade;

import com.boxbox.market.trade.dto.MarketOrderHistoryResponse.Item;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
class MarketOrderHistoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    List<Item> find(String loginId, BigInteger roundId, String type, Cursor cursor, int limit) {
        var sql = new StringBuilder("""
                SELECT o.id, s.round_id, s.driver_id, o.type, o.quantity, o.total_amount,
                       COALESCE(o.realized_pnl, 0) AS realized_pnl, o.executed_at
                FROM trade_orders o
                JOIN users u ON u.id = o.user_id
                JOIN market_stocks s ON s.id = o.stock_id
                WHERE u.login_id = :loginId AND u.deleted_at IS NULL AND u.status IN ('ACTIVE', 'SUSPENDED')
                  AND o.status = 'FILLED' AND o.type IN ('BUY', 'SELL', 'SETTLEMENT_SELL')
                  AND o.executed_at IS NOT NULL AND o.quantity > 0 AND o.total_amount IS NOT NULL
                """);
        var params = new MapSqlParameterSource("loginId", loginId).addValue("limit", limit);
        if (roundId != null) {
            sql.append(" AND s.round_id = :roundId");
            params.addValue("roundId", new BigDecimal(roundId));
        }
        if (type != null) {
            sql.append(" AND o.type = :type");
            params.addValue("type", type);
        }
        if (cursor != null) {
            sql.append(" AND (o.executed_at < :at OR (o.executed_at = :at AND o.id < :id))");
            params.addValue("at", LocalDateTime.ofInstant(cursor.executedAt(), ZoneOffset.UTC));
            params.addValue("id", new BigDecimal(cursor.orderId()));
        }
        sql.append(" ORDER BY o.executed_at DESC, o.id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (row, index) -> {
            var quantity = row.getBigDecimal("quantity");
            var amount = row.getBigDecimal("total_amount");
            return new Item(row.getString("id"), row.getString("round_id"), row.getString("driver_id"),
                    row.getString("type"), quantity.toBigIntegerExact(), amount.toBigIntegerExact(),
                    amount.divide(quantity, 8, RoundingMode.HALF_UP), row.getBigDecimal("realized_pnl").toBigIntegerExact(),
                    row.getObject("executed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        });
    }

    record Cursor(Instant executedAt, BigInteger orderId) { }
}
