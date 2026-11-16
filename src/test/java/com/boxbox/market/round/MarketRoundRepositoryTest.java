package com.boxbox.market.round;

import com.boxbox.common.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import tools.jackson.databind.json.JsonMapper;

class MarketRoundRepositoryTest {
    private MarketRoundRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        repository = new MarketRoundRepository(new NamedParameterJdbcTemplate(source));
        jdbc.execute("""
                CREATE TABLE races (id BIGINT PRIMARY KEY, season_year INT,
                    round_number INT, name VARCHAR(120), status VARCHAR(24))
                """);
        jdbc.execute("""
                CREATE TABLE market_rounds (id BIGINT PRIMARY KEY, race_id BIGINT, season_year INT,
                    status VARCHAR(24), open_at DATETIME(6), close_at DATETIME(6), publish_at DATETIME(6),
                    ranking_published_at DATETIME(6), rule_version VARCHAR(40), status_version BIGINT,
                    rule_snapshot VARCHAR(1000), settlement_status VARCHAR(24))
                """);
        jdbc.execute("CREATE TABLE chat_rooms (id BIGINT PRIMARY KEY, race_id BIGINT)");
    }

    @Test
    void listsStocksUsingFrozenTeamAndKeepsEqualPricesAcrossPages() {
        addRound(1, 2026, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        jdbc.execute("CREATE TABLE drivers (id BIGINT PRIMARY KEY, name VARCHAR(120), car_number INT)");
        jdbc.execute("CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(120))");
        jdbc.execute("""
                CREATE TABLE market_stocks (id BIGINT PRIMARY KEY, round_id BIGINT, season_year INT,
                    driver_id BIGINT, team_id BIGINT, base_rank INT, starting_price BIGINT, current_price BIGINT,
                    price_version BIGINT, updated_at DATETIME(6), total_buy_quantity BIGINT)
                """);
        jdbc.update("INSERT INTO drivers VALUES (1, 'Driver A', 44), (2, 'Driver B', 63), (3, 'Driver C', 4)");
        jdbc.update("INSERT INTO teams VALUES (10, 'Team A'), (20, 'Team B')");
        jdbc.update("""
                INSERT INTO market_stocks VALUES
                    (1, 1, 2026, 1, 10, 2, 100, 120, 1, '2026-11-16 00:00:00', 5),
                    (2, 1, 2026, 2, 10, 1, 150, 120, 1, '2026-11-16 00:00:00', 0),
                    (3, 1, 2026, 3, 20, 3, 100, 150, 1, '2026-11-16 00:00:00', 0)
                """);
        var service = new MarketStockService(repository,
                new MarketStockRepository(new NamedParameterJdbcTemplate(jdbc)), JsonMapper.builder().build());
        var start = Instant.parse("2026-11-16T00:00:00Z");
        var close = Instant.parse("2026-11-17T00:00:00Z");

        var beforeOpen = service.list("1", "10", "PRICE_DESC", null, "1", start.minusNanos(1));
        assertTrue(beforeOpen.items().isEmpty());
        assertNull(beforeOpen.nextCursor());
        assertFalse(beforeOpen.hasNext());

        var first = service.list("1", "10", "PRICE_DESC", null, "1", start);
        var item = first.items().getFirst();
        assertEquals("1", item.driver().driverId());
        assertEquals("Team A", item.driver().teamName());
        assertEquals(20, item.changeRate().intValueExact());
        assertTrue(item.tradable());
        assertTrue(first.hasNext());

        var next = service.list("1", "10", "PRICE_DESC", first.nextCursor(), "1", close);
        assertEquals("2", next.items().getFirst().driver().driverId());
        assertEquals(-20, next.items().getFirst().changeRate().intValueExact());
        assertFalse(next.items().getFirst().tradable());
        assertNull(next.nextCursor());
        assertFalse(next.hasNext());

        assertEquals("3", service.list("1", null, "CHANGE_RATE_DESC", null, "20", start)
                .items().getFirst().driver().driverId());
        assertTrue(service.list("1", "99", "BASE_RANK", null, "20", start).items().isEmpty());
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> service.list("1", null, "PRICE_DESC", first.nextCursor(), "20", start)).getCode());
        assertEquals("ROUND_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.list("2", null, "BASE_RANK", null, "20", start)).getCode());

        assertTrue(service.detail("1", "1", start).settlementScenarios().isEmpty());
        jdbc.update("UPDATE market_rounds SET rule_snapshot = ? WHERE id = 1",
                """
                {"resultBonusByRank":[8,0,0,0,0,0,0,0,0]}
                """);
        var detail = service.detail("1", "1", close);
        assertEquals("1", detail.driver().driverId());
        assertFalse(detail.tradable());
        var win = detail.settlementScenarios().getFirst();
        assertEquals(1, win.positionScore());
        assertEquals(8, win.resultBonus());
        assertEquals(9, win.finalScore());
        assertEquals(109, win.settlementPrice().intValueExact());
        var ninth = detail.settlementScenarios().getLast();
        assertEquals(-35, ninth.positionScore());
        assertEquals(-30, ninth.finalScore());
        assertEquals(70, ninth.settlementPrice().intValueExact());
        assertEquals("STOCK_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail("1", "1", start.minusNanos(1))).getCode());
        assertEquals("STOCK_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail("1", "99", start)).getCode());
    }

    @Test
    void prefersOpenRoundUntilExclusiveCloseThenSelectsNextScheduledRound() {
        addRound(1, 2026, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(2, 2026, "SCHEDULED", "2026-11-18 00:00:00", "2026-11-20 00:00:00");
        addRound(3, 2026, "SCHEDULED", "2026-11-21 00:00:00", "2026-11-22 00:00:00");
        addRound(4, 2026, "CANCELLED", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(5, 2025, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        jdbc.update("INSERT INTO chat_rooms (id, race_id) VALUES (50, 1)");

        var open = repository.current(2026, Instant.parse("2026-11-16T00:00:00Z")).orElseThrow();
        assertEquals("1", open.id());
        assertEquals("50", open.chatRoomId());
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), open.openAt());

        var upcoming = repository.current(2026, Instant.parse("2026-11-17T00:00:00Z")).orElseThrow();
        assertEquals("2", upcoming.id());
        assertNull(upcoming.chatRoomId());
    }

    @Test
    void omitsCancelledRaceAndRoundWithoutRequiredTimes() {
        addRound(1, 2026, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(2, 2026, "SCHEDULED", null, "2026-11-20 00:00:00");
        jdbc.update("UPDATE races SET status = 'CANCELLED' WHERE id = 1");

        assertTrue(repository.current(2026, Instant.parse("2026-11-16T00:00:00Z")).isEmpty());
    }

    @Test
    void returnsRequestedRoundWithStoredRuleAndSettlementStatus() {
        addRound(1, 2025, "SETTLED", "2025-11-16 00:00:00", "2025-11-17 00:00:00");
        jdbc.update("UPDATE market_rounds SET rule_snapshot = ?, settlement_status = ? WHERE id = 1",
                "{\"tradeImpactRate\":0.02}", "COMPLETED");
        var service = new MarketRoundService(repository, JsonMapper.builder().build(), 2026);

        var detail = service.detail("1");

        assertEquals("1", detail.roundId());
        assertEquals("SETTLED", detail.status());
        assertEquals(new BigDecimal("0.02"), detail.tradeImpactRate());
        assertEquals("STARTING_PRICE", detail.settlementBase());
        assertEquals("COMPLETED", detail.settlementStatus());
        assertEquals("ROUND_NOT_FOUND", assertThrows(ApiException.class, () -> service.detail("2")).getCode());
    }

    private void addRound(int id, int season, String status, String openAt, String closeAt) {
        jdbc.update("INSERT INTO races VALUES (?, ?, ?, ?, 'SCHEDULED')", id, season, id, "Grand Prix " + id);
        jdbc.update("""
                INSERT INTO market_rounds VALUES (?, ?, ?, ?, ?, ?, '2026-11-24 00:00:00', NULL, 'rule-1', 1, '{}', 'NOT_STARTED')
                """, id, id, season, status, openAt, closeAt);
    }
}
