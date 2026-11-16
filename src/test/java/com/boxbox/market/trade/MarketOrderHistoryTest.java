package com.boxbox.market.trade;

import com.boxbox.common.api.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketOrderHistoryTest {
    @Test
    void scopesFilledOrdersToTheMemberAndPaginatesTimestampTies() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE users (id BIGINT, login_id VARCHAR(20), status VARCHAR(16), deleted_at DATETIME)");
        jdbc.execute("CREATE TABLE market_stocks (id BIGINT, round_id BIGINT, driver_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE trade_orders (id BIGINT, user_id BIGINT, stock_id BIGINT, type VARCHAR(24),
                    status VARCHAR(16), quantity BIGINT, total_amount BIGINT, realized_pnl BIGINT, executed_at DATETIME(6))
                """);
        jdbc.update("INSERT INTO users VALUES (1, 'demo', 'ACTIVE', NULL), (2, 'other', 'ACTIVE', NULL)");
        jdbc.update("INSERT INTO market_stocks VALUES (1, 21, 3), (2, 22, 4)");
        jdbc.update("""
                INSERT INTO trade_orders VALUES
                    (10, 1, 1, 'BUY', 'FILLED', 2, 100005, NULL, '2026-11-16 00:00:00.123456'),
                    (11, 1, 1, 'SELL', 'FILLED', 1, 50010, 7, '2026-11-16 00:00:00.123456'),
                    (12, 1, 2, 'SETTLEMENT_SELL', 'FILLED', 1, 60000, 100, '2026-11-15 00:00:00'),
                    (13, 2, 1, 'BUY', 'FILLED', 1, 50000, NULL, '2026-11-16 00:00:00.123456'),
                    (14, 1, 1, 'ORDER', 'REJECTED', NULL, NULL, NULL, NULL)
                """);
        var service = new MarketOrderHistoryService(
                new MarketOrderHistoryRepository(new NamedParameterJdbcTemplate(jdbc)));

        var first = service.history("demo", "21", null, null, "1");
        assertEquals("11", first.items().getFirst().orderId());
        assertTrue(first.hasNext());

        var second = service.history("demo", "21", null, first.nextCursor(), "1");
        var buy = second.items().getFirst();
        assertEquals("10", buy.orderId());
        assertEquals(BigInteger.ZERO, buy.realizedPnl());
        assertEquals(0, new BigDecimal("50002.5").compareTo(buy.averageUnitPrice()));
        assertFalse(second.hasNext());
        assertNull(second.nextCursor());

        assertEquals("12", service.history("demo", null, "SETTLEMENT_SELL", null, "20").items().getFirst().orderId());
        assertThrows(ApiException.class, () -> service.history("demo", "22", null, first.nextCursor(), "1"));
        assertTrue(service.history(null, null, null, null, "20").items().isEmpty());
    }
}
