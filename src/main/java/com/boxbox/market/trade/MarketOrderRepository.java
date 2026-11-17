package com.boxbox.market.trade;

import com.boxbox.market.event.MarketEventLog;
import java.util.Map;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.trade.dto.MarketOrderResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MarketOrderRepository {
    private static final int[] CANDLE_INTERVALS = {60, 300, 3600, 86400};
    private final JdbcTemplate jdbc;

    User lockUser(String loginId) {
        return jdbc.query("""
                SELECT id, coin_balance FROM users
                WHERE login_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL FOR UPDATE
                """, (row, index) -> new User(integer(row, "id"), row.getLong("coin_balance")), loginId)
                .stream().findFirst().orElseThrow(ApiException::unauthorized);
    }

    Optional<Replay> replay(User user, String key) {
        return jdbc.query("""
                SELECT request_hash, response_status, response_body FROM trade_orders
                WHERE user_id = ? AND idempotency_key = ?
                """, (row, index) -> new Replay(row.getBytes("request_hash"), row.getInt("response_status"),
                row.getString("response_body")), decimal(user.id()), key).stream().findFirst();
    }

    Optional<Quote> quote(User user, BigInteger quoteId) {
        return jdbc.query("""
                SELECT stock_id, side, quantity, unit_prices, total_amount, fee, average_unit_price,
                       price_after, price_version, expires_at, used_at
                FROM trade_quotes WHERE id = ? AND user_id = ? FOR UPDATE
                """, (row, index) -> new Quote(integer(row, "stock_id"), row.getString("side"),
                row.getInt("quantity"), row.getString("unit_prices"), row.getLong("total_amount"),
                row.getLong("fee"), row.getBigDecimal("average_unit_price"), row.getLong("price_after"),
                integer(row, "price_version"), time(row, "expires_at"), time(row, "used_at")),
                decimal(quoteId), decimal(user.id())).stream().findFirst();
    }

    Optional<Market> lockMarket(BigInteger roundId) {
        return jdbc.query("""
                SELECT status, open_at, close_at, rule_snapshot FROM market_rounds WHERE id = ? FOR UPDATE
                """, (row, index) -> new Market(row.getString("status"), time(row, "open_at"),
                time(row, "close_at"), row.getString("rule_snapshot")), decimal(roundId)).stream().findFirst();
    }

    Optional<Stock> lockStock(BigInteger stockId, BigInteger roundId) {
        return jdbc.query("""
                SELECT driver_id, starting_price, theoretical_price, price_version
                FROM market_stocks WHERE id = ? AND round_id = ? FOR UPDATE
                """, (row, index) -> new Stock(row.getString("driver_id"), row.getLong("starting_price"),
                row.getBigDecimal("theoretical_price"), integer(row, "price_version")), decimal(stockId),
                decimal(roundId)).stream().findFirst();
    }

    Position position(User user, Quote quote) {
        return jdbc.query("SELECT quantity, total_cost FROM positions WHERE user_id = ? AND stock_id = ?",
                (row, index) -> new Position(integer(row, "quantity"), row.getLong("total_cost")),
                decimal(user.id()), decimal(quote.stockId())).stream().findFirst()
                .orElse(new Position(BigInteger.ZERO, 0));
    }

    void saveOrder(User user, String key, byte[] hash, String request, BigInteger quoteId,
                   Quote quote, MarketOrderResponse order, Long allocated, Long pnl, String response) {
        jdbc.update("""
                INSERT INTO trade_orders (id, user_id, stock_id, quote_id, idempotency_key, request_hash,
                    request_body, type, status, quantity, unit_prices, total_amount, fee, allocated_cost,
                    realized_pnl, cash_balance_after, holding_quantity_after, price_after, price_version_after,
                    response_status, response_body, executed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'FILLED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 200, ?, ?, ?, ?)
                """, Long.parseLong(order.orderId()), decimal(user.id()), decimal(quote.stockId()), decimal(quoteId),
                key, hash, request, order.type(), order.quantity(), quote.unitPrices(), order.totalAmount(),
                order.fee(), allocated, pnl, order.cashBalance(), decimal(order.holdingQuantity()),
                order.currentPrice(), decimal(order.priceVersion()), response, utc(order.executedAt()),
                utc(order.executedAt()), utc(order.executedAt()));
    }

    void saveRejection(User user, String key, byte[] hash, String request, int status, String response, Instant now) {
        jdbc.update("""
                INSERT INTO trade_orders (id, user_id, idempotency_key, request_hash, request_body,
                    type, status, response_status, response_body, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ORDER', 'REJECTED', ?, ?, ?, ?)
                """, Long.parseLong(MarketQuoteRepository.newId()), decimal(user.id()), key, hash, request,
                status, response, utc(now), utc(now));
    }

    void apply(User user, BigInteger quoteId, Quote quote, MarketOrderResponse order, boolean buy, long newCost,
               long delta, BigDecimal theoretical) {
        var when = utc(order.executedAt());
        var userId = decimal(user.id());
        var stockId = decimal(quote.stockId());
        var holding = decimal(order.holdingQuantity());
        jdbc.update("UPDATE users SET coin_balance = ?, updated_at = ? WHERE id = ?", order.cashBalance(), when, userId);
        jdbc.update("""
                INSERT INTO positions (user_id, stock_id, quantity, total_cost, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = ?, total_cost = ?, version = version + 1, updated_at = ?
                """, userId, stockId, holding, newCost, when, when, holding, newCost, when);
        jdbc.update("""
                UPDATE market_stocks SET current_price = ?, theoretical_price = ?, price_version = ?,
                    total_buy_quantity = total_buy_quantity + ?, updated_at = ?
                WHERE id = ?
                """, order.currentPrice(), theoretical, decimal(order.priceVersion()), buy ? order.quantity() : 0,
                when, stockId);
        jdbc.update("UPDATE trade_quotes SET used_at = ?, updated_at = ? WHERE id = ?",
                when, when, decimal(quoteId));
        jdbc.update("""
                INSERT INTO coin_ledger (user_id, order_id, event_key, entry_type, amount_delta, balance_after, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, Long.parseLong(order.orderId()),
                "ORDER:" + order.orderId(), order.type(), delta, order.cashBalance(), when);
        recordCandles(stockId, order);
        MarketEventLog.append(jdbc, new BigDecimal(order.roundId()), "PRICE_UPDATED", Map.of(
                "roundId", order.roundId(), "driverId", order.driverId(), "currentPrice", order.currentPrice(),
                "priceVersion", order.priceVersion()), when);
    }

    private void recordCandles(BigDecimal stockId, MarketOrderResponse order) {
        var prices = order.unitPrices();
        long high = prices.stream().mapToLong(Long::longValue).max().orElseThrow();
        long low = prices.stream().mapToLong(Long::longValue).min().orElseThrow();
        for (int interval : CANDLE_INTERVALS) {
            long seconds = Math.floorDiv(order.executedAt().getEpochSecond(), interval) * interval;
            jdbc.update("""
                    INSERT INTO stock_price_candles (interval_seconds, bucket_at, stock_id, open_price,
                        high_price, low_price, close_price, volume)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE high_price = GREATEST(high_price, ?), low_price = LEAST(low_price, ?),
                        close_price = ?, volume = volume + ?
                    """, interval, utc(Instant.ofEpochSecond(seconds)), stockId, prices.getFirst(), high, low,
                    prices.getLast(), order.quantity(), high, low, prices.getLast(), order.quantity());
        }
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        return row.getBigDecimal(column).toBigIntegerExact();
    }

    private static BigDecimal decimal(BigInteger value) {
        return new BigDecimal(value);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    record User(BigInteger id, long cashBalance) { }
    record Quote(BigInteger stockId, String side, int quantity, String unitPrices, long totalAmount, long fee,
                 BigDecimal average, long priceAfter, BigInteger priceVersion, Instant expiresAt, Instant usedAt) { }
    record Market(String status, Instant openAt, Instant closeAt, String snapshot) { }
    record Stock(String driverId, long startingPrice, BigDecimal theoreticalPrice, BigInteger priceVersion) { }
    record Position(BigInteger quantity, long totalCost) { }
    record Replay(byte[] hash, int status, String body) { }
}
