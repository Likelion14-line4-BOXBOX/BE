package com.boxbox.market.portfolio;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.stock.dto.MarketStockResponses.Driver;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketPortfolioRepository {
    private final JdbcTemplate jdbc;

    Account account(String loginId) {
        return jdbc.query("""
                SELECT id, coin_balance FROM users
                WHERE login_id = ? AND deleted_at IS NULL AND status IN ('ACTIVE', 'SUSPENDED')
                """, (row, index) -> new Account(number(row, "id"), number(row, "coin_balance")), loginId)
                .stream().findFirst().orElseThrow(ApiException::unauthorized);
    }

    List<Holding> positions(BigInteger userId, BigInteger roundId) {
        return jdbc.query("""
                SELECT s.driver_id, d.name AS driver_name, d.car_number, s.team_id, t.name AS team_name,
                       p.quantity, p.total_cost, s.current_price, s.base_rank
                FROM positions p
                JOIN market_stocks s ON s.id = p.stock_id
                JOIN drivers d ON d.id = s.driver_id
                JOIN teams t ON t.id = s.team_id
                WHERE p.user_id = ? AND s.round_id = ? AND p.quantity > 0
                ORDER BY s.base_rank, s.id
                """, (row, index) -> {
            Integer carNumber = row.getObject("car_number", Integer.class);
            if (carNumber == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                        "종목 기준 정보를 읽을 수 없습니다.");
            }
            var driver = new Driver(row.getString("driver_id"), row.getString("driver_name"), carNumber,
                    row.getString("team_id"), row.getString("team_name"));
            return new Holding(driver, number(row, "quantity"), number(row, "total_cost"),
                    number(row, "current_price"), row.getInt("base_rank"));
        }, new BigDecimal(userId), new BigDecimal(roundId));
    }

    Settlement settlement(BigInteger userId, BigInteger roundId) {
        return jdbc.query("""
                SELECT round_start_assets, CASE WHEN completed_at IS NOT NULL THEN settlement_pnl END AS settlement_pnl
                FROM user_settlements
                WHERE user_id = ? AND round_id = ?
                ORDER BY settlement_version DESC, id DESC LIMIT 1
                """, (row, index) -> new Settlement(number(row, "round_start_assets"), number(row, "settlement_pnl")),
                new BigDecimal(userId), new BigDecimal(roundId)).stream().findFirst().orElse(new Settlement(null, null));
    }

    BigInteger realizedSales(BigInteger userId, BigInteger roundId) {
        var value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.realized_pnl), 0)
                FROM trade_orders o JOIN market_stocks s ON s.id = o.stock_id
                WHERE o.user_id = ? AND s.round_id = ? AND o.status = 'FILLED' AND o.type = 'SELL'
                """, BigDecimal.class, new BigDecimal(userId), new BigDecimal(roundId));
        return value.toBigIntegerExact();
    }

    BigInteger openingBalance(BigInteger userId, Instant openAt, Instant now) {
        Instant boundary = openAt != null && openAt.isBefore(now) ? openAt : now;
        var key = new BigDecimal(userId);
        return jdbc.query("""
                SELECT balance_after FROM coin_ledger
                WHERE user_id = ? AND created_at < ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, (row, index) -> number(row, "balance_after"), key, LocalDateTime.ofInstant(boundary, ZoneOffset.UTC))
                .stream().findFirst().orElseGet(() -> jdbc.query("""
                        SELECT balance_after FROM coin_ledger
                        WHERE user_id = ? AND entry_type = 'INITIAL_GRANT' AND created_at <= ?
                        ORDER BY created_at, id LIMIT 1
                        """, (row, index) -> number(row, "balance_after"), key, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                        .stream().findFirst().orElse(BigInteger.ZERO));
    }

    BigInteger seasonStartingAssets(BigInteger userId, int season) {
        return jdbc.query("""
                SELECT u.round_start_assets FROM user_settlements u
                JOIN market_rounds m ON m.id = u.round_id
                JOIN races r ON r.id = m.race_id AND r.season_year = m.season_year
                WHERE u.user_id = ? AND m.season_year = ? AND r.round_number = 1
                  AND u.round_start_assets IS NOT NULL
                ORDER BY u.settlement_version DESC, u.id DESC LIMIT 1
                """, (row, index) -> number(row, "round_start_assets"), new BigDecimal(userId), season)
                .stream().findFirst().orElse(null);
    }

    Long seasonRank(BigInteger userId, int season, Instant now) {
        return jdbc.query("""
                SELECT e.rank FROM ranking_entries e
                WHERE e.user_id = ? AND e.snapshot_id = (
                    SELECT id FROM ranking_snapshots
                    WHERE season_year = ? AND scope = 'SEASON' AND type = 'TOTAL_ASSETS'
                      AND published_at <= ?
                    ORDER BY published_at DESC, id DESC LIMIT 1
                )
                """, (row, index) -> row.getLong("rank"),
                new BigDecimal(userId), season, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .stream().findFirst().orElse(null);
    }

    private static BigInteger number(ResultSet row, String column) throws SQLException {
        var value = row.getBigDecimal(column);
        return value == null ? null : value.toBigIntegerExact();
    }

    record Account(BigInteger id, BigInteger cash) { }
    record Holding(Driver driver, BigInteger quantity, BigInteger cost, BigInteger price, int baseRank) { }
    record Settlement(BigInteger startingAssets, BigInteger pnl) { }
}
