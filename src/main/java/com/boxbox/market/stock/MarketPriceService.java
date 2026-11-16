package com.boxbox.market.stock;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.MarketRoundRepository;
import com.boxbox.market.stock.dto.MarketStockResponses.Candle;
import com.boxbox.market.stock.dto.MarketStockResponses.Prices;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketPriceService {
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Map<String, Integer> INTERVALS = Map.of("1m", 60, "5m", 300, "1h", 3600, "1d", 86400);

    private final MarketRoundRepository rounds;
    private final MarketStockRepository stocks;
    private final MarketPriceRepository prices;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Prices prices(String roundId, String driverId, String intervalValue, String fromValue, String toValue,
                         String cursor, String sizeValue, Instant now) {
        var roundKey = MarketStockService.stockLookupId(roundId);
        var driverKey = MarketStockService.stockLookupId(driverId);
        Integer interval = INTERVALS.get(intervalValue);
        if (interval == null) {
            throw ApiException.invalidRequest("interval은 1m, 5m, 1h, 1d 중 하나여야 합니다.");
        }
        int size = MarketStockService.parseSize(sizeValue);
        Instant from = parseTime(fromValue);
        Instant to = parseTime(toValue);
        if (from != null && to != null && (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0)) {
            throw ApiException.invalidRequest("to는 from 이후여야 하며 조회 구간은 최대 31일입니다.");
        }

        var round = rounds.findById(roundKey).orElseThrow(MarketStockService::stockNotFound).round();
        if (!MarketStockService.isPublished(round, now)) {
            throw MarketStockService.stockNotFound();
        }
        var stock = stocks.findByDriver(roundKey, round.season(), driverKey)
                .orElseThrow(MarketStockService::stockNotFound);
        Instant start = from != null ? from : (to != null ? later(round.openAt(), to.minus(MAX_RANGE)) : round.openAt());
        Instant end = to != null ? to : earlier(now, start.plus(MAX_RANGE));
        start = later(start, round.openAt());
        end = earlier(end, now);
        if (round.closeAt() != null) {
            end = earlier(end, round.closeAt());
        }

        Instant firstBucket = Instant.ofEpochSecond(Math.floorDiv(start.getEpochSecond(), interval) * interval);
        String scope = roundId + "|" + driverId + "|" + intervalValue + "|" + from + "|" + to + "|";
        Instant pageStart = cursor == null ? firstBucket : cursorStart(cursor, scope, firstBucket, interval);
        if (!start.isBefore(end) || !pageStart.isBefore(end)) {
            return new Prices(List.of(), null, false);
        }

        Instant next = pageStart.plusSeconds((long) size * interval);
        Instant pageEnd = earlier(next, end);
        var stored = prices.candles(stock.id(), interval, pageStart, pageEnd).stream()
                .collect(Collectors.toMap(Candle::timestamp, Function.identity()));
        BigInteger previous = prices.previousClose(stock.id(), interval, pageStart).orElse(stock.startingPrice());
        var items = new ArrayList<Candle>(size);
        for (Instant at = pageStart; at.isBefore(pageEnd); at = at.plusSeconds(interval)) {
            var candle = stored.get(at);
            if (candle == null) {
                candle = new Candle(at, previous, previous, previous, previous, BigInteger.ZERO);
            }
            items.add(candle);
            previous = candle.close();
        }

        boolean hasNext = next.isBefore(end);
        String nextCursor = hasNext ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + next).getBytes(StandardCharsets.UTF_8)) : null;
        return new Prices(List.copyOf(items), nextCursor, hasNext);
    }

    private static Instant cursorStart(String cursor, String scope, Instant firstBucket, int interval) {
        try {
            if (cursor.length() <= 256) {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (value.startsWith(scope)) {
                    Instant time = Instant.parse(value.substring(scope.length()));
                    if (time.isAfter(firstBucket) && time.getNano() == 0 && time.getEpochSecond() % interval == 0) {
                        return time;
                    }
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException ignored) {
            throw ApiException.invalidRequest("cursor를 확인해 주세요.");
        }
        throw ApiException.invalidRequest("cursor를 확인해 주세요.");
    }

    private static Instant parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.endsWith("Z")) {
                Instant time = Instant.parse(value);
                if (!time.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                        && time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
                    return time;
                }
            }
        } catch (DateTimeParseException ignored) {
            throw ApiException.invalidRequest("from과 to는 UTC ISO 8601 시각이어야 합니다.");
        }
        throw ApiException.invalidRequest("from과 to는 UTC ISO 8601 시각이어야 합니다.");
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
