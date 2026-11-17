package com.boxbox.market.portfolio;

import com.boxbox.market.round.MarketRoundRepository;
import com.boxbox.market.round.MarketRoundService;
import com.boxbox.market.round.dto.MarketRoundResponses;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketPortfolioTest {
    @Test
    void valuesOnlyTheCurrentUsersPositionsAndUsesTheOpeningLedgerBalance() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE users (id BIGINT, login_id VARCHAR(20), coin_balance BIGINT, status VARCHAR(16), deleted_at DATETIME)");
        jdbc.execute("CREATE TABLE positions (user_id BIGINT, stock_id BIGINT, quantity BIGINT, total_cost BIGINT)");
        jdbc.execute("CREATE TABLE market_stocks (id BIGINT, round_id BIGINT, driver_id BIGINT, team_id BIGINT, current_price BIGINT, base_rank INT)");
        jdbc.execute("CREATE TABLE drivers (id BIGINT, name VARCHAR(100), car_number INT)");
        jdbc.execute("CREATE TABLE teams (id BIGINT, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE trade_orders (user_id BIGINT, stock_id BIGINT, status VARCHAR(16), type VARCHAR(16), realized_pnl BIGINT)");
        jdbc.execute("CREATE TABLE coin_ledger (id BIGINT, user_id BIGINT, entry_type VARCHAR(24), created_at DATETIME, balance_after BIGINT)");
        jdbc.execute("CREATE TABLE user_settlements (id BIGINT, user_id BIGINT, round_id BIGINT, round_start_assets BIGINT, settlement_pnl BIGINT, settlement_version INT, status VARCHAR(16), published_at DATETIME(6))");
        jdbc.execute("""
                CREATE TABLE user_season_baselines (season_year INT, user_id BIGINT, starting_assets BIGINT,
                    PRIMARY KEY (season_year, user_id))
                """);
        jdbc.execute("CREATE TABLE ranking_snapshots (id BIGINT, season_year INT, scope VARCHAR(16), type VARCHAR(24), published_at DATETIME)");
        jdbc.execute("CREATE TABLE ranking_entries (snapshot_id BIGINT, user_id BIGINT, rank INT)");

        jdbc.update("INSERT INTO users VALUES (1, 'demo', 899995, 'ACTIVE', NULL), (2, 'other', 500, 'ACTIVE', NULL)");
        jdbc.update("INSERT INTO positions VALUES (1, 1, 2, 100005), (2, 1, 7, 250000)");
        jdbc.update("INSERT INTO market_stocks VALUES (1, 21, 3, 4, 50010, 1)");
        jdbc.update("INSERT INTO drivers VALUES (3, 'Driver', 23)");
        jdbc.update("INSERT INTO teams VALUES (4, 'Team')");
        jdbc.update("INSERT INTO coin_ledger VALUES (1, 1, 'INITIAL_GRANT', '2026-11-15 00:00:00', 1000000)");
        jdbc.update("INSERT INTO ranking_snapshots VALUES (1, 2026, 'SEASON', 'TOTAL_ASSETS', '2026-11-16 00:00:00')");
        jdbc.update("INSERT INTO ranking_entries VALUES (1, 1, 12), (1, 2, 13)");

        var rounds = mock(MarketRoundRepository.class);
        var current = mock(MarketRoundService.class);
        var now = Instant.parse("2026-11-16T00:01:00Z");
        var openAt = Instant.parse("2026-11-16T00:00:00Z");
        var version = BigInteger.ONE;
        var row = new MarketRoundRepository.RoundRow("21", "21", 2026, 21, "Race", "OPEN",
                openAt, now.plusSeconds(60), now.plusSeconds(120), null, "test", version, null);
        when(rounds.findById(BigInteger.valueOf(21)))
                .thenReturn(Optional.of(new MarketRoundRepository.DetailRow(row, "{}", "NOT_STARTED")));
        when(current.current(now)).thenReturn(new MarketRoundResponses.Current(new MarketRoundResponses.Round(
                "21", "21", 2026, 21, "Race", "OPEN", openAt, now.plusSeconds(60),
                now.plusSeconds(120), null, "test", version, null)));

        var service = new MarketPortfolioService(new MarketPortfolioRepository(jdbc), current, rounds);
        var result = service.portfolio("demo", null, now);
        assertEquals("21", result.roundId());
        assertEquals(BigInteger.valueOf(1000015), result.totalAssets());
        assertEquals(BigInteger.valueOf(1000000), result.roundStartAssets());
        assertEquals(BigInteger.valueOf(15), result.unrealizedPnl());
        assertEquals(0, new BigDecimal("0.0015").compareTo(result.returnRate()));
        assertEquals(12L, result.seasonRank());
        assertEquals(1, result.positions().size());
        assertEquals(BigInteger.TWO, result.positions().getFirst().quantity());
        assertEquals(0, new BigDecimal("50002.5").compareTo(result.positions().getFirst().averageBuyPrice()));
        assertNull(result.seasonStartAssets());

        assertNull(result.seasonReturnRate());
        jdbc.update("INSERT INTO user_season_baselines VALUES (2025, 1, 500000), (2026, 2, 500000), (2026, 1, NULL)");
        var missing = service.portfolio("demo", "21", now);
        assertNull(missing.seasonStartAssets());
        assertNull(missing.seasonReturnRate());

        jdbc.update("UPDATE user_season_baselines SET starting_assets = 0 WHERE season_year = 2026 AND user_id = 1");
        var zero = service.portfolio("demo", "21", now);
        assertEquals(BigInteger.ZERO, zero.seasonStartAssets());
        assertNull(zero.seasonReturnRate());

        jdbc.update("UPDATE user_season_baselines SET starting_assets = 1000000 WHERE season_year = 2026 AND user_id = 1");
        var configured = service.portfolio("demo", "21", now);
        assertEquals(BigInteger.valueOf(1000000), configured.seasonStartAssets());
        assertEquals(0, new BigDecimal("0.0015").compareTo(configured.seasonReturnRate()));

        jdbc.update("INSERT INTO trade_orders VALUES (1, 1, 'FILLED', 'SELL', 10)");
        jdbc.update("INSERT INTO user_settlements VALUES (1, 1, 21, 1000000, 40, 1, 'COMPLETED', NULL)");
        assertEquals(BigInteger.TEN, service.portfolio("demo", "21", now).realizedPnl());
        jdbc.update("UPDATE user_settlements SET published_at = '2026-11-16 00:02:00' WHERE id = 1");
        assertEquals(BigInteger.TEN, service.portfolio("demo", "21", now).realizedPnl());
        jdbc.update("UPDATE user_settlements SET published_at = '2026-11-16 00:01:00' WHERE id = 1");
        assertEquals(BigInteger.valueOf(50), service.portfolio("demo", "21", now).realizedPnl());
        jdbc.update("UPDATE user_settlements SET status = 'FAILED' WHERE id = 1");
        assertEquals(BigInteger.TEN, service.portfolio("demo", "21", now).realizedPnl());

        var guest = service.portfolio(null, "21", now);
        assertEquals(BigInteger.ZERO, guest.cashBalance());
        assertTrue(guest.positions().isEmpty());
        assertNull(guest.seasonRank());
    }
}
