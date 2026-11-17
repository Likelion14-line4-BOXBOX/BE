package com.boxbox.market.settlement;

import com.boxbox.common.api.ApiException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSettlementTest {
    @Test
    void readsLatestVersionForEachRoundAndHidesUnpublishedResults() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE users (id BIGINT, login_id VARCHAR(20), status VARCHAR(16), deleted_at DATETIME)");
        jdbc.execute("CREATE TABLE market_rounds (id BIGINT, race_id BIGINT, season_year INT)");
        jdbc.execute("CREATE TABLE races (id BIGINT, season_year INT, name VARCHAR(100), round_number INT)");
        jdbc.execute("""
                CREATE TABLE user_settlements (id BIGINT, user_id BIGINT, round_id BIGINT, settlement_version INT,
                    status VARCHAR(16), round_start_assets BIGINT, ending_assets BIGINT, round_pnl BIGINT,
                    return_rate DECIMAL(20,8), round_rank INT, settlement_proceeds BIGINT, published_at DATETIME(6))
                """);
        jdbc.update("INSERT INTO users VALUES (1, 'demo', 'ACTIVE', NULL), (2, 'other', 'ACTIVE', NULL)");
        jdbc.update("INSERT INTO market_rounds VALUES (21, 1, 2026), (22, 2, 2026), (99, 3, 2025)");
        jdbc.update("INSERT INTO races VALUES (1, 2026, 'Race A', 1), (2, 2026, 'Race B', 2), (3, 2025, 'Race C', 3)");
        jdbc.update("""
                INSERT INTO user_settlements VALUES
                    (1, 1, 22, 1, 'PUBLISHED', 100, 106, 6, 6, 12, 20, '2026-11-14 00:00:00'),
                    (2, 1, 22, 2, 'COMPLETED', 100, 109, 9, 9, 6, 24, '2026-11-18 00:00:00'),
                    (3, 1, 21, 1, 'PUBLISHED', 100, 104, 4, 4, 3, 18, '2026-11-12 00:00:00'),
                    (4, 2, 21, 1, 'PUBLISHED', 100, 105, 5, 5, 7, 19, '2026-11-12 00:00:00'),
                    (5, 1, 99, 1, 'FAILED', NULL, 104, 4, 4, NULL, 18, NULL)
                """);
        var service = new MarketSettlementService(new MarketSettlementRepository(new NamedParameterJdbcTemplate(jdbc)),
                JsonMapper.builder().build());
        var now = Instant.parse("2026-11-17T00:00:00Z");

        var first = service.list("demo", "2026", null, "1", now);
        assertTrue(first.hasNext());
        var pending = first.items().getFirst();
        assertEquals("22", pending.roundId());
        assertEquals("PROCESSING", pending.status());
        assertNull(pending.endingAssets());
        assertNull(pending.roundPnl());
        assertNull(pending.returnRate());
        assertNull(pending.rank());
        assertNull(pending.settlementProceeds());
        assertNull(pending.publishedAt());

        var second = service.list("demo", "2026", first.nextCursor(), "1", now);
        assertFalse(second.hasNext());
        assertNull(second.nextCursor());
        var published = second.items().getFirst();
        assertEquals("21", published.roundId());
        assertEquals("PUBLISHED", published.status());
        assertEquals(BigInteger.valueOf(4), published.roundPnl());
        assertEquals(BigInteger.valueOf(18), published.settlementProceeds());
        assertEquals(3L, published.rank());

        assertThrows(ApiException.class, () -> service.list("demo", "2025", first.nextCursor(), "1", now));
        assertTrue(service.list(null, null, null, "20", now).items().isEmpty());

        jdbc.execute("ALTER TABLE market_rounds ADD rule_version VARCHAR(40) DEFAULT 'current-rule'");
        jdbc.execute("ALTER TABLE user_settlements ADD rule_snapshot VARCHAR(1000)");
        jdbc.execute("ALTER TABLE user_settlements ADD season_rank INT");
        jdbc.execute("ALTER TABLE user_settlements ADD season_return_rate DECIMAL(20,8)");
        jdbc.update("UPDATE user_settlements SET rule_snapshot = ?, season_rank = 10 WHERE id = 3",
                """
                {"ruleVersion":"frozen-rule","driverNames":{"1":"Driver at settlement"}}
                """);
        jdbc.execute("CREATE TABLE market_stocks (id BIGINT, round_id BIGINT, driver_id BIGINT)");
        jdbc.execute("CREATE TABLE drivers (id BIGINT, name VARCHAR(100))");
        jdbc.execute("""
                CREATE TABLE user_settlement_items (user_settlement_id BIGINT, user_id BIGINT, round_id BIGINT,
                    settlement_version INT, stock_id BIGINT, base_rank INT, finish_rank INT, position_score INT,
                    result_bonus INT, final_score INT, starting_price BIGINT, settlement_price BIGINT,
                    quantity BIGINT, total_cost BIGINT, proceeds BIGINT, pnl BIGINT)
                """);
        jdbc.update("INSERT INTO drivers VALUES (1, 'Driver A')");
        jdbc.update("INSERT INTO market_stocks VALUES (1, 21, 1)");
        jdbc.update("""
                INSERT INTO user_settlement_items VALUES
                    (3, 1, 21, 1, 1, 21, NULL, -21, 0, -21, 23, 18, 1, 14, 18, 4),
                    (3, 2, 21, 1, 1, 21, NULL, -21, 0, -21, 23, 18, 2, 33, 36, 3),
                    (3, 1, 21, 2, 1, 21, NULL, -21, 0, -21, 23, 18, 2, 33, 36, 3)
                """);

        var detail = service.detail("demo", "21", now);
        assertEquals("frozen-rule", detail.ruleVersion());
        assertEquals(1L, detail.settlementVersion());
        assertEquals(10L, detail.seasonRank());
        assertEquals(1, detail.items().size());
        var item = detail.items().getFirst();
        assertEquals("Driver at settlement", item.driverName());
        jdbc.update("UPDATE drivers SET name = 'Renamed Driver' WHERE id = 1");
        assertEquals("Driver at settlement", service.detail("demo", "21", now).items().getFirst().driverName());
        jdbc.update("UPDATE user_settlements SET rule_snapshot = NULL WHERE id = 3");
        assertEquals("Renamed Driver", service.detail("demo", "21", now).items().getFirst().driverName());
        assertNull(item.finishRank());
        assertEquals(BigInteger.valueOf(18), item.settlementPrice());
        assertEquals(BigInteger.valueOf(4), item.settlementPnl());
        assertEquals("SETTLEMENT_NOT_PUBLISHED", assertThrows(ApiException.class,
                () -> service.detail("demo", "22", now)).getCode());
        assertEquals("SETTLEMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail("other", "22", now)).getCode());
        assertEquals("SETTLEMENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail(null, "21", now)).getCode());
    }
}
