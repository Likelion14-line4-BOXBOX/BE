package com.boxbox.market.round;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.dto.MarketQuoteResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
class MarketQuoteRepository {
    private static final SecureRandom QUOTE_IDS = new SecureRandom();
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    Optional<Stock> findStock(BigInteger roundId, int season, BigInteger driverId) {
        return jdbc.query("""
                SELECT id, starting_price, current_price, theoretical_price, price_version
                FROM market_stocks
                WHERE round_id = :roundId AND season_year = :season AND driver_id = :driverId
                """, Map.of("roundId", new BigDecimal(roundId), "season", season, "driverId", new BigDecimal(driverId)),
                (row, index) -> new Stock(row.getBigDecimal("id").toBigIntegerExact(), row.getLong("starting_price"),
                        row.getLong("current_price"), row.getBigDecimal("theoretical_price"),
                        row.getBigDecimal("price_version").toBigIntegerExact())).stream().findFirst();
    }

    Account account(String loginId, BigInteger stockId) {
        return jdbc.query("""
                SELECT u.id, u.coin_balance, COALESCE(p.quantity, 0) AS quantity, COALESCE(p.total_cost, 0) AS total_cost
                FROM users u
                LEFT JOIN positions p ON p.user_id = u.id AND p.stock_id = :stockId
                WHERE u.login_id = :loginId AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                """, Map.of("stockId", new BigDecimal(stockId), "loginId", loginId),
                (row, index) -> new Account(row.getBigDecimal("id").toBigIntegerExact(), row.getLong("coin_balance"),
                        row.getBigDecimal("quantity").toBigIntegerExact(), row.getLong("total_cost")))
                .stream().findFirst().orElseThrow(ApiException::unauthorized);
    }

    void save(Account account, Stock stock, MarketQuoteResponse quote, Instant now) {
        jdbc.update("""
                INSERT INTO trade_quotes (
                    id, user_id, stock_id, side, quantity, unit_prices, total_amount, fee,
                    average_unit_price, price_after, price_version, expires_at, created_at, updated_at
                ) VALUES (
                    :id, :userId, :stockId, :side, :quantity, :unitPrices, :totalAmount, :fee,
                    :average, :priceAfter, :priceVersion, :expiresAt, :now, :now
                )
                """, new MapSqlParameterSource("id", Long.parseLong(quote.quoteId()))
                .addValue("userId", new BigDecimal(account.userId())).addValue("stockId", new BigDecimal(stock.id()))
                .addValue("side", quote.side()).addValue("quantity", quote.quantity())
                .addValue("unitPrices", json.writeValueAsString(quote.unitPrices())).addValue("totalAmount", quote.totalAmount())
                .addValue("fee", quote.fee()).addValue("average", quote.averageUnitPrice())
                .addValue("priceAfter", quote.priceAfter()).addValue("priceVersion", new BigDecimal(quote.priceVersion()))
                .addValue("expiresAt", LocalDateTime.ofInstant(quote.expiresAt(), ZoneOffset.UTC))
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    static String newId() {
        return Long.toString(QUOTE_IDS.nextLong(1, Long.MAX_VALUE));
    }

    record Stock(BigInteger id, long startingPrice, long currentPrice, BigDecimal theoreticalPrice,
                 BigInteger priceVersion) { }

    record Account(BigInteger userId, long cashBalance, BigInteger holdingQuantity, long totalCost) { }
}
