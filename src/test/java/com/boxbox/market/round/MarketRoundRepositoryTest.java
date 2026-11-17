package com.boxbox.market.round;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.stock.MarketPriceRepository;
import com.boxbox.market.stock.MarketPriceService;
import com.boxbox.market.stock.MarketStockRepository;
import com.boxbox.market.stock.MarketStockService;
import com.boxbox.market.trade.MarketOrderRepository;
import com.boxbox.market.trade.MarketOrderService;
import com.boxbox.market.trade.MarketQuoteRepository;
import com.boxbox.market.trade.MarketQuoteService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
        addStockFixtures();
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

        var defaultScenarios = service.detail("1", "1", start).settlementScenarios();
        assertEquals(5, defaultScenarios.size());
        assertEquals(6, defaultScenarios.get(1).resultBonus());
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
        var retirement = detail.settlementScenarios().getLast();
        assertNull(retirement.finishRank());
        assertEquals(-30, retirement.positionScore());
        assertEquals(0, retirement.resultBonus());
        assertEquals(-30, retirement.finalScore());
        assertEquals(70, retirement.settlementPrice().intValueExact());

        jdbc.update("UPDATE market_stocks SET base_rank = 6 WHERE id = 1");
        var middle = service.detail("1", "1", start).settlementScenarios();
        assertEquals(List.of(3, 5, 6, 9),
                middle.subList(0, 4).stream().map(scenario -> scenario.finishRank()).toList());
        assertEquals(-30, middle.getLast().positionScore());

        jdbc.update("UPDATE market_stocks SET base_rank = 21 WHERE id = 1");
        var bottom = service.detail("1", "1", start).settlementScenarios();
        assertEquals(List.of(18, 19, 20, 21),
                bottom.subList(0, 4).stream().map(scenario -> scenario.finishRank()).toList());
        assertEquals(-30, bottom.getLast().finalScore());

        jdbc.update("UPDATE market_stocks SET base_rank = 22 WHERE id = 1");
        assertEquals(-30, service.detail("1", "1", start).settlementScenarios().getLast().finalScore());
        assertEquals("STOCK_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail("1", "1", start.minusNanos(1))).getCode());
        assertEquals("STOCK_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.detail("1", "99", start)).getCode());
    }

    @Test
    void keepsClosedRoundUntilSettlementIsPublicAndNextRoundOpens() {
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

        jdbc.update("UPDATE market_rounds SET status = 'CLOSED' WHERE id = 1");
        assertEquals("1", repository.current(2026, Instant.parse("2026-11-17T00:00:00Z")).orElseThrow().id());
        jdbc.update("""
                UPDATE market_rounds SET status = 'OPEN' WHERE id = 2
                """);
        assertEquals("1", repository.current(2026, Instant.parse("2026-11-18T00:00:00Z")).orElseThrow().id());
        jdbc.update("""
                UPDATE market_rounds SET settlement_status = 'COMPLETED', publish_at = '2026-11-18 01:00:00'
                WHERE id = 1
                """);
        assertEquals("1", repository.current(2026, Instant.parse("2026-11-18T00:59:59Z")).orElseThrow().id());
        var upcoming = repository.current(2026, Instant.parse("2026-11-18T01:00:00Z")).orElseThrow();
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

    @Test
    void chartsStoredCandlesAndCarriesPricesAcrossEmptyBucketsAndPages() {
        addStockFixtures();
        jdbc.execute("""
                CREATE TABLE stock_price_candles (stock_id BIGINT, interval_seconds INT, bucket_at DATETIME(6),
                    open_price BIGINT, high_price BIGINT, low_price BIGINT, close_price BIGINT, volume BIGINT,
                    PRIMARY KEY (interval_seconds, bucket_at, stock_id))
                """);
        jdbc.update("""
                INSERT INTO stock_price_candles VALUES
                    (1, 60, '2026-11-16 00:01:00', 100, 120, 95, 110, 7),
                    (1, 300, '2026-11-16 00:00:00', 100, 140, 95, 130, 20),
                    (2, 60, '2026-11-16 00:02:00', 150, 150, 150, 150, 4)
                """);
        var namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        var service = new MarketPriceService(repository, new MarketStockRepository(namedJdbc),
                new MarketPriceRepository(namedJdbc));
        var now = Instant.parse("2026-11-16T00:04:00Z");
        var first = service.prices("1", "1", "1m", null, null, null, "2", now);
        assertEquals(100, first.items().getFirst().close().intValueExact());
        assertEquals(0, first.items().getFirst().volume().intValueExact());
        assertEquals(120, first.items().getLast().high().intValueExact());
        assertEquals(7, first.items().getLast().volume().intValueExact());
        assertTrue(first.hasNext());

        var second = service.prices("1", "1", "1m", null, null, first.nextCursor(), "2", now);
        assertEquals(Instant.parse("2026-11-16T00:02:00Z"), second.items().getFirst().timestamp());
        assertEquals(110, second.items().getFirst().close().intValueExact());
        assertEquals(0, second.items().getFirst().volume().intValueExact());
        assertFalse(second.hasNext());
        assertNull(second.nextCursor());

        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> service.prices("1", "1", "5m", null, null, first.nextCursor(), "2", now)).getCode());
        assertEquals("STOCK_NOT_FOUND", assertThrows(ApiException.class,
                () -> service.prices("1", "99", "1m", null, null, null, "2", now)).getCode());
    }

    @Test
    void savesBuyAndSellQuotesWithoutChangingCashOrHoldings() {
        addStockFixtures();
        jdbc.execute("ALTER TABLE market_stocks ADD theoretical_price DECIMAL(28,8)");
        jdbc.update("""
                UPDATE market_stocks SET starting_price = 50000, current_price = 50000, theoretical_price = 50000
                WHERE id = 1
                """);
        jdbc.execute("""
                CREATE TABLE users (id BIGINT PRIMARY KEY, login_id VARCHAR(20), status VARCHAR(16),
                                    coin_balance BIGINT, deleted_at DATETIME(6), updated_at DATETIME(6))
                """);
        jdbc.execute("""
                CREATE TABLE positions (user_id BIGINT, stock_id BIGINT, quantity BIGINT, total_cost BIGINT,
                    version BIGINT DEFAULT 0, created_at DATETIME(6), updated_at DATETIME(6),
                    PRIMARY KEY (user_id, stock_id))
                """);
        jdbc.execute("""
                CREATE TABLE trade_quotes (
                    id BIGINT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, side VARCHAR(8), quantity BIGINT,
                    unit_prices VARCHAR(1000), total_amount BIGINT, fee BIGINT, average_unit_price DECIMAL(28,8),
                    price_after BIGINT, price_version BIGINT, expires_at DATETIME(6), used_at DATETIME(6),
                    created_at DATETIME(6), updated_at DATETIME(6)
                )
                """);
        jdbc.update("INSERT INTO users VALUES (1, 'demo', 'ACTIVE', 1000000, NULL, NULL)");
        jdbc.update("INSERT INTO positions (user_id, stock_id, quantity, total_cost) VALUES (1, 1, 2, 100001)");
        var json = JsonMapper.builder().build();
        var service = new MarketQuoteService(repository, new MarketRoundService(repository, json, 2026),
                new MarketQuoteRepository(new NamedParameterJdbcTemplate(jdbc), json));
        var now = Instant.parse("2026-11-16T00:01:00Z");
        var buyBody = json.readTree("""
                {"driverId":"1","side":"BUY","quantity":2}
                """);
        var buy = service.create("demo", "1", buyBody, now);
        assertEquals(List.of(50000L, 50005L), buy.unitPrices());
        assertEquals(100005, buy.totalAmount());
        assertEquals(50010, buy.priceAfter());
        assertEquals(0, new BigDecimal("0.005").compareTo(buy.changeRateFromStartingPrice()));
        assertEquals(now.plusSeconds(5), buy.expiresAt());
        assertNull(buy.estimatedRealizedPnl());

        var sellBody = json.readTree("""
                {"driverId":"1","side":"SELL","quantity":2}
                """);
        var sell = service.create("demo", "1", sellBody, now);
        assertEquals(List.of(50000L, 50000L), sell.unitPrices());
        assertEquals(50000, sell.priceAfter());
        assertEquals(-1L, sell.estimatedRealizedPnl());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM trade_quotes WHERE used_at IS NULL", Integer.class));
        assertEquals(1000000L, jdbc.queryForObject("SELECT coin_balance FROM users", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT quantity FROM positions", Long.class));

        assertEquals("MARKET_CLOSED", assertThrows(ApiException.class, () ->
                service.create("demo", "1", buyBody, Instant.parse("2026-11-17T00:00:00Z"))).getCode());
        jdbc.update("UPDATE users SET coin_balance = 100000");
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(ApiException.class, () ->
                service.create("demo", "1", buyBody, now)).getCode());
        jdbc.update("UPDATE positions SET quantity = 1");
        assertEquals("INSUFFICIENT_HOLDINGS", assertThrows(ApiException.class, () ->
                service.create("demo", "1", sellBody, now)).getCode());

        jdbc.update("UPDATE users SET coin_balance = 1000000");
        jdbc.update("UPDATE positions SET quantity = 2");
        jdbc.execute("""
                CREATE TABLE trade_orders (
                    id BIGINT PRIMARY KEY, user_id BIGINT, stock_id BIGINT, quote_id BIGINT,
                    idempotency_key VARCHAR(64), request_hash BINARY(32), request_body VARCHAR(1000),
                    type VARCHAR(16), status VARCHAR(16), quantity BIGINT, unit_prices VARCHAR(1000),
                    total_amount BIGINT, fee BIGINT, allocated_cost BIGINT, realized_pnl BIGINT,
                    cash_balance_after BIGINT, holding_quantity_after BIGINT, price_after BIGINT,
                    price_version_after BIGINT, response_status INT, response_body VARCHAR(3000),
                    executed_at DATETIME(6), created_at DATETIME(6), updated_at DATETIME(6)
                )
                """);
        jdbc.execute("""
                CREATE TABLE coin_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT,
                    order_id BIGINT, event_key VARCHAR(160), entry_type VARCHAR(24), amount_delta BIGINT,
                    balance_after BIGINT, created_at DATETIME(6))
                """);
        jdbc.execute("""
                CREATE TABLE stock_price_candles (stock_id BIGINT, interval_seconds INT, bucket_at DATETIME(6),
                    open_price BIGINT, high_price BIGINT, low_price BIGINT, close_price BIGINT, volume BIGINT,
                    PRIMARY KEY (interval_seconds, bucket_at, stock_id))
                """);
        var executions = new MarketOrderService(new MarketOrderRepository(jdbc),
                new MarketRoundService(repository, json, 2026), json, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));
        var order = json.createObjectNode().put("quoteId", buy.quoteId());
        String key = UUID.randomUUID().toString();
        var filled = executions.execute("demo", "1", key, order);
        assertEquals(200, filled.status());
        assertEquals(899995L, filled.body().path("data").path("cashBalance").longValue());
        assertEquals(4, filled.body().path("data").path("holdingQuantity").intValue());
        assertEquals(filled, executions.execute("demo", "1", key, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coin_ledger", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM stock_price_candles WHERE volume = 2", Integer.class));

        var alreadyUsed = executions.execute("demo", "1", UUID.randomUUID().toString(), order);
        assertEquals(409, alreadyUsed.status());
        assertEquals("QUOTE_ALREADY_USED", alreadyUsed.body().path("error").path("code").stringValue());
        var stale = executions.execute("demo", "1", UUID.randomUUID().toString(),
                json.createObjectNode().put("quoteId", sell.quoteId()));
        assertEquals("PRICE_CHANGED", stale.body().path("error").path("code").stringValue());

        var newSell = service.create("demo", "1", sellBody, now);
        var sold = executions.execute("demo", "1", UUID.randomUUID().toString(),
                json.createObjectNode().put("quoteId", newSell.quoteId()));
        assertEquals(200, sold.status());
        assertEquals(2, sold.body().path("data").path("holdingQuantity").intValue());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM stock_price_candles WHERE volume = 4", Integer.class));

        var beforeClose = service.create("demo", "1", sellBody, now);
        jdbc.update("UPDATE market_rounds SET close_at = ? WHERE id = 1",
                java.time.LocalDateTime.ofInstant(now.plusSeconds(1), ZoneOffset.UTC));
        var closed = executions.execute("demo", "1", UUID.randomUUID().toString(),
                json.createObjectNode().put("quoteId", beforeClose.quoteId()));
        assertEquals(409, closed.status());
        assertEquals("MARKET_CLOSED", closed.body().path("error").path("code").stringValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM coin_ledger", Integer.class));
    }

    private void addStockFixtures() {
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
    }

    private void addRound(int id, int season, String status, String openAt, String closeAt) {
        jdbc.update("INSERT INTO races VALUES (?, ?, ?, ?, 'SCHEDULED')", id, season, id, "Grand Prix " + id);
        jdbc.update("""
                INSERT INTO market_rounds VALUES (?, ?, ?, ?, ?, ?, '2026-11-24 00:00:00', NULL, 'rule-1', 1, '{}', 'NOT_STARTED')
                """, id, id, season, status, openAt, closeAt);
    }
}
