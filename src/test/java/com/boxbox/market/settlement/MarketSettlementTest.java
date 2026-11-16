package com.boxbox.market.settlement;

import com.boxbox.common.api.ApiException;
import java.math.BigInteger;
import java.time.Instant;
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
        var service = new MarketSettlementService(new MarketSettlementRepository(new NamedParameterJdbcTemplate(jdbc)));
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
    }
}
