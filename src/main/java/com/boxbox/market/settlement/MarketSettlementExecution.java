package com.boxbox.market.settlement;

import com.boxbox.market.settlement.MarketSettlementResultService.DriverResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class MarketSettlementExecution {
    private static final SecureRandom IDS = new SecureRandom();
    private static final List<Integer> BONUSES = List.of(8, 6, 5, 3, 3, 1, 1, 1, 1, 1);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    @Transactional
    public boolean execute(MarketSettlementResultService.MatchedResult result, Instant now) {
        if (now.isBefore(result.publishAt())) {
            return false;
        }

        var roundId = new BigDecimal(result.roundId());
        var rounds = jdbc.query("""
                SELECT settlement_version, season_year, open_at FROM market_rounds
                WHERE id = ? AND status = 'CLOSED' AND settlement_status = 'NOT_STARTED'
                  AND close_at <= ? AND publish_at <= ?
                FOR UPDATE
                """, (row, index) -> new Round(row.getInt("settlement_version"), row.getInt("season_year"),
                row.getObject("open_at", LocalDateTime.class)), roundId, utc(now), utc(now));
        if (rounds.isEmpty()) {
            return false;
        }
        var round = rounds.getFirst();
        var drivers = storePrices(roundId, round.version(), result);

        var snapshot = json.createObjectNode().put("sessionKey", result.sessionKey());
        var names = snapshot.putObject("driverNames");
        result.items().forEach(driver -> names.put(driver.driverId(), driver.driverName()));
        var participants = jdbc.queryForList("""
                SELECT p.user_id FROM positions p JOIN market_stocks s ON s.id = p.stock_id
                WHERE s.round_id = ? AND p.quantity > 0
                UNION
                SELECT o.user_id FROM trade_orders o JOIN market_stocks s ON s.id = o.stock_id
                WHERE s.round_id = ? AND o.status = 'FILLED' AND o.type IN ('BUY', 'SELL')
                ORDER BY user_id
                """, BigDecimal.class, roundId, roundId);
        for (var user : participants) {
            settleUser(roundId, round, user, snapshot.toString(), utc(now), drivers);
        }
        publishRoundRankings(roundId, round, utc(now));
        publishSeasonRankings(roundId, round, utc(now));
        jdbc.update("""
                UPDATE market_rounds SET settlement_status = 'COMPLETED', settlement_completed_at = ?,
                    ranking_published_at = ?, updated_at = ?
                WHERE id = ?
                """, utc(now), utc(now), utc(now), roundId);
        return true;
    }

    private void publishRoundRankings(BigDecimal roundId, Round round, LocalDateTime now) {
        long assetSnapshot = createRoundRanking(roundId, round, now, "TOTAL_ASSETS", "ending_assets");
        createRoundRanking(roundId, round, now, "RETURN_RATE", "return_rate");
        jdbc.update("""
                UPDATE user_settlements s JOIN ranking_entries e ON e.user_id = s.user_id
                SET s.round_rank = e.`rank`
                WHERE e.snapshot_id = ? AND s.round_id = ? AND s.settlement_version = ?
                """, assetSnapshot, roundId, round.version());
    }

    private long createRoundRanking(BigDecimal roundId, Round round, LocalDateTime now, String type, String metric) {
        long snapshotId = nextId();
        jdbc.update("""
                INSERT INTO ranking_snapshots (id, season_year, round_id, scope, type, status, snapshot_at, published_at)
                VALUES (?, ?, ?, 'ROUND', ?, 'PUBLISHED', ?, ?)
                """, snapshotId, round.season(), roundId, type, now, now);
        jdbc.update("""
                INSERT INTO ranking_entries (snapshot_id, user_id, `rank`, total_assets, round_pnl, return_rate)
                SELECT ?, user_id, RANK() OVER (ORDER BY %s DESC), ending_assets, round_pnl, return_rate
                FROM user_settlements
                WHERE round_id = ? AND settlement_version = ? AND status = 'COMPLETED' AND %s IS NOT NULL
                """.formatted(metric, metric), snapshotId, roundId, round.version());
        return snapshotId;
    }

    private void publishSeasonRankings(BigDecimal roundId, Round round, LocalDateTime now) {
        long assets = createSeasonRanking(round.season(), "TOTAL_ASSETS", "total_assets", now);
        long returns = createSeasonRanking(round.season(), "RETURN_RATE", "return_rate", now);
        jdbc.update("""
                UPDATE user_settlements s
                LEFT JOIN ranking_entries a ON a.snapshot_id = ? AND a.user_id = s.user_id
                LEFT JOIN ranking_entries r ON r.snapshot_id = ? AND r.user_id = s.user_id
                SET s.season_rank = a.`rank`, s.season_return_rate = r.return_rate
                WHERE s.round_id = ? AND s.settlement_version = ?
                """, assets, returns, roundId, round.version());
    }

    private long createSeasonRanking(int season, String type, String metric, LocalDateTime now) {
        long snapshotId = nextId();
        jdbc.update("""
                INSERT INTO ranking_snapshots (id, season_year, scope, type, status, snapshot_at, published_at)
                VALUES (?, ?, 'SEASON', ?, 'PUBLISHED', ?, ?)
                """, snapshotId, season, type, now, now);
        jdbc.update("""
                INSERT INTO ranking_entries (snapshot_id, user_id, `rank`, total_assets, round_pnl, return_rate)
                WITH versions AS (
                    SELECT s.user_id, s.round_id, s.ending_assets, s.round_pnl, s.id, r.round_number,
                        ROW_NUMBER() OVER (PARTITION BY s.user_id, s.round_id
                            ORDER BY s.settlement_version DESC, s.id DESC) AS version_order
                    FROM user_settlements s
                    JOIN market_rounds m ON m.id = s.round_id
                    JOIN races r ON r.id = m.race_id
                    WHERE m.season_year = ? AND s.status IN ('COMPLETED', 'PUBLISHED')
                        AND s.published_at <= ? AND s.ending_assets IS NOT NULL
                ), latest AS (
                    SELECT v.*, ROW_NUMBER() OVER (PARTITION BY v.user_id ORDER BY v.round_number DESC, v.id DESC) AS seq
                    FROM versions v WHERE v.version_order = 1
                ), metrics AS (
                    SELECT l.user_id, l.ending_assets AS total_assets, l.round_pnl,
                        ROUND(100.0 * (CAST(l.ending_assets AS DECIMAL(24, 0)) - b.starting_assets)
                            / NULLIF(b.starting_assets, 0), 8) AS return_rate
                    FROM latest l LEFT JOIN user_season_baselines b ON b.user_id = l.user_id AND b.season_year = ?
                    WHERE l.seq = 1
                )
                SELECT ?, user_id, RANK() OVER (ORDER BY %s DESC), total_assets, round_pnl, return_rate
                FROM metrics WHERE %s IS NOT NULL
                """.formatted(metric, metric), season, now, season, snapshotId);
        return snapshotId;
    }

    private Map<BigInteger, DriverResult> storePrices(BigDecimal roundId, int version,
                                                  MarketSettlementResultService.MatchedResult result) {
        var stored = new HashMap<BigInteger, DriverResult>();
        var bonuses = json.readTree(result.ruleSnapshot()).path("resultBonusByRank");
        if (!bonuses.isMissingNode() && !bonuses.isArray()) {
            throw new IllegalStateException("라운드 정산 보너스 규칙을 읽을 수 없습니다.");
        }
        for (var driver : result.items()) {
            int bonus = 0;
            if (!driver.retired()) {
                int index = driver.finishRank() - 1;
                bonus = index < BONUSES.size() ? BONUSES.get(index) : 0;
                if (index < bonuses.size()) {
                    var value = bonuses.get(index);
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
                        throw new IllegalStateException("라운드 정산 보너스 규칙을 읽을 수 없습니다.");
                    }
                    bonus = value.intValue();
                }
            }
            var price = driver.settlementPrice(bonus);
            int updated = jdbc.update("""
                    UPDATE market_stocks SET settlement_price = ?, position_score = ?, result_bonus = ?,
                        final_score = ?, settlement_version = ?
                    WHERE id = ? AND round_id = ? AND base_rank = ? AND starting_price = ?
                    """, price.price().longValueExact(), price.positionScore(), price.resultBonus(),
                    price.finalScore(), version, new BigDecimal(driver.stockId()), roundId,
                    driver.baseRank(), driver.startingPrice().longValueExact());
            if (updated != 1 || stored.putIfAbsent(driver.stockId(), driver) != null) {
                throw new IllegalStateException("정산 대상 종목이 변경됐습니다.");
            }
        }
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM market_stocks WHERE round_id = ?", Integer.class, roundId);
        if (stored.size() != count || stored.isEmpty()) {
            throw new IllegalStateException("정산 결과가 일부 종목에 누락됐습니다.");
        }
        return stored;
    }

    private void settleUser(BigDecimal roundId, Round round, BigDecimal user, String snapshot, LocalDateTime now,
                            Map<BigInteger, DriverResult> drivers) {
        long balance = jdbc.queryForObject("SELECT coin_balance FROM users WHERE id = ? FOR UPDATE", Long.class, user);
        long start = openingBalance(user, round.openAt(), now);
        long sales = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.realized_pnl), 0) FROM trade_orders o
                JOIN market_stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND s.round_id = ? AND o.status = 'FILLED' AND o.type = 'SELL'
                """, BigDecimal.class, user, roundId).longValueExact();
        var holdings = jdbc.query("""
                SELECT p.stock_id, p.quantity, p.total_cost, s.settlement_price, s.base_rank,
                       s.position_score, s.result_bonus, s.final_score, s.starting_price
                FROM positions p JOIN market_stocks s ON s.id = p.stock_id
                WHERE p.user_id = ? AND s.round_id = ? AND p.quantity > 0
                ORDER BY p.stock_id FOR UPDATE
                """, (row, index) -> new Holding(row.getBigDecimal("stock_id"),
                row.getBigDecimal("quantity").longValueExact(), row.getLong("total_cost"),
                row.getLong("settlement_price"), row.getInt("base_rank"), row.getInt("position_score"),
                row.getInt("result_bonus"), row.getInt("final_score"), row.getLong("starting_price")),
                user, roundId);

        long proceeds = 0;
        long liquidationPnl = 0;
        var items = new ArrayList<Payout>();
        for (var holding : holdings) {
            long amount = Math.multiplyExact(holding.price(), holding.quantity());
            long pnl = Math.subtractExact(amount, holding.cost());
            balance = Math.addExact(balance, amount);
            proceeds = Math.addExact(proceeds, amount);
            liquidationPnl = Math.addExact(liquidationPnl, pnl);
            items.add(new Payout(holding, amount, pnl, balance, nextId()));
        }
        long pnl = Math.addExact(sales, liquidationPnl);
        var rate = start == 0 ? null : BigDecimal.valueOf(pnl).movePointRight(2)
                .divide(BigDecimal.valueOf(start), 8, RoundingMode.HALF_UP);
        long summaryId = nextId();
        jdbc.update("""
                INSERT INTO user_settlements (id, round_id, user_id, round_start_assets, ending_assets, round_pnl,
                    settlement_proceeds, settlement_pnl, return_rate, completed_at, published_at,
                    settlement_version, status, joined_at, rule_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                """, summaryId, roundId, user, start, Math.addExact(start, pnl), pnl, proceeds, liquidationPnl,
                rate, now, now, round.version(), now, snapshot);
        for (var item : items) {
            var driver = drivers.get(item.holding().stock().toBigIntegerExact());
            savePayout(roundId, round.version(), user, summaryId, item, driver.finishRank(), now);
        }
        if (!items.isEmpty()) {
            jdbc.update("UPDATE users SET coin_balance = ?, updated_at = ? WHERE id = ?", balance, now, user);
        }
    }

    private long openingBalance(BigDecimal user, LocalDateTime openAt, LocalDateTime now) {
        return jdbc.query("""
                SELECT balance_after FROM coin_ledger
                WHERE user_id = ? AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT 1
                """, (row, index) -> row.getLong(1), user, openAt).stream().findFirst()
                .orElseGet(() -> jdbc.query("""
                        SELECT balance_after FROM coin_ledger
                        WHERE user_id = ? AND entry_type = 'INITIAL_GRANT' AND created_at <= ?
                        ORDER BY created_at, id LIMIT 1
                        """, (row, index) -> row.getLong(1), user, now).stream().findFirst().orElse(0L));
    }

    private void savePayout(BigDecimal roundId, int version, BigDecimal user, long summaryId,
                            Payout payout, Integer finishRank, LocalDateTime now) {
        var h = payout.holding();
        jdbc.update("""
                INSERT INTO trade_orders (id, user_id, stock_id, type, status, quantity, total_amount, fee,
                    allocated_cost, realized_pnl, cash_balance_after, holding_quantity_after, price_after,
                    executed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'SETTLEMENT_SELL', 'FILLED', ?, ?, 0, ?, ?, ?, 0, ?, ?, ?, ?)
                """, payout.orderId(), user, h.stock(), h.quantity(), payout.amount(), h.cost(),
                payout.pnl(), payout.balance(), h.price(), now, now, now);
        jdbc.update("""
                INSERT INTO coin_ledger (user_id, order_id, event_key, entry_type, amount_delta, balance_after, created_at)
                VALUES (?, ?, ?, 'SETTLEMENT_SELL', ?, ?, ?)
                """, user, payout.orderId(), "SETTLEMENT:" + roundId + ":" + version + ":" + user + ":" + h.stock(),
                payout.amount(), payout.balance(), now);
        jdbc.update("""
                INSERT INTO user_settlement_items (user_settlement_id, stock_id, order_id, user_id,
                    quantity, total_cost, proceeds, pnl, round_id, settlement_version, base_rank,
                    finish_rank, position_score, result_bonus, final_score, starting_price, settlement_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, summaryId, h.stock(), payout.orderId(), user, h.quantity(), h.cost(), payout.amount(),
                payout.pnl(), roundId, version, h.baseRank(), finishRank, h.positionScore(), h.bonus(),
                h.finalScore(), h.startingPrice(), h.price());
        jdbc.update("""
                UPDATE positions SET quantity = 0, total_cost = 0, version = version + 1, updated_at = ?
                WHERE user_id = ? AND stock_id = ?
                """, now, user, h.stock());
    }

    private static long nextId() {
        return IDS.nextLong(1, Long.MAX_VALUE);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private record Round(int version, int season, LocalDateTime openAt) {}

    private record Holding(BigDecimal stock, long quantity, long cost, long price, int baseRank,
                           int positionScore, int bonus, int finalScore, long startingPrice) {}

    private record Payout(Holding holding, long amount, long pnl, long balance, long orderId) {}
}
