package com.boxbox.market.round;

import com.boxbox.market.round.dto.MarketStockResponses.Candle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketPriceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    Optional<BigInteger> previousClose(BigInteger stockId, int interval, Instant start) {
        return jdbc.query("""
                SELECT close_price FROM stock_price_candles
                WHERE stock_id = :stockId AND interval_seconds = :interval AND bucket_at < :start
                ORDER BY bucket_at DESC LIMIT 1
                """, parameters(stockId, interval, start),
                (row, index) -> row.getBigDecimal("close_price").toBigIntegerExact()).stream().findFirst();
    }

    List<Candle> candles(BigInteger stockId, int interval, Instant start, Instant end) {
        return jdbc.query("""
                SELECT bucket_at, open_price, high_price, low_price, close_price, volume
                FROM stock_price_candles
                WHERE stock_id = :stockId AND interval_seconds = :interval
                  AND bucket_at >= :start AND bucket_at < :end
                ORDER BY bucket_at
                """, parameters(stockId, interval, start).addValue("end", LocalDateTime.ofInstant(end, ZoneOffset.UTC)),
                (row, index) -> new Candle(row.getObject("bucket_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        row.getBigDecimal("open_price").toBigIntegerExact(),
                        row.getBigDecimal("high_price").toBigIntegerExact(),
                        row.getBigDecimal("low_price").toBigIntegerExact(),
                        row.getBigDecimal("close_price").toBigIntegerExact(),
                        row.getBigDecimal("volume").toBigIntegerExact()));
    }

    private static MapSqlParameterSource parameters(BigInteger stockId, int interval, Instant start) {
        return new MapSqlParameterSource("stockId", new BigDecimal(stockId))
                .addValue("interval", interval)
                .addValue("start", LocalDateTime.ofInstant(start, ZoneOffset.UTC));
    }
}
