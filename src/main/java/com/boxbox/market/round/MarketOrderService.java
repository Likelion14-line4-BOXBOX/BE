package com.boxbox.market.round;

import com.boxbox.common.api.ApiErrorResponse;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.round.dto.MarketOrderResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class MarketOrderService {
    private final MarketOrderRepository orders;
    private final MarketRoundService rules;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result execute(String loginId, String roundId, String idempotencyKey, JsonNode body) {
        String key = normalizeKey(idempotencyKey);
        var quoteValue = body.path("quoteId");
        if (!body.isObject() || body.size() != 1 || !quoteValue.isString() || quoteValue.stringValue().isBlank()) {
            throw ApiException.invalidRequest("quoteId를 확인해 주세요.");
        }

        String quoteId = quoteValue.stringValue();
        String request = json.writeValueAsString(new OrderRequest(roundId, quoteId));
        byte[] hash = fingerprint(request);
        // Serialize requests for one account before checking the key or changing its cash and positions.
        var user = orders.lockUser(loginId);
        var existing = orders.replay(user, key);
        if (existing.isPresent()) {
            var replay = existing.get();
            if (!MessageDigest.isEqual(hash, replay.hash())) {
                throw conflict("IDEMPOTENCY_CONFLICT", "같은 주문 키에 다른 요청을 사용할 수 없습니다.");
            }
            return new Result(replay.status(), json.readTree(replay.body()));
        }

        Execution execution;
        try {
            execution = prepare(user, roundId, quoteId);
        } catch (ApiException rejection) {
            if (!rejection.getStatus().is4xxClientError()) {
                throw rejection;
            }
            var error = ApiErrorResponse.of(rejection.getCode(), rejection.getMessage());
            String response = json.writeValueAsString(error);
            int status = rejection.getStatus().value();
            orders.saveRejection(user, key, hash, request, status, response, clock.instant());
            return new Result(status, json.readTree(response));
        }

        var order = execution.order();
        var meta = new ApiResponse.Meta(UUID.randomUUID().toString(), order.executedAt());
        String response = json.writeValueAsString(new ApiResponse<>(order, meta));
        orders.saveOrder(user, key, hash, request, execution.quoteId(), execution.quote(), order,
                execution.allocated(), execution.pnl(), response);
        orders.apply(user, execution.quoteId(), execution.quote(), order, execution.buy(),
                execution.newCost(), execution.delta(), execution.theoretical());
        return new Result(200, json.readTree(response));
    }

    private Execution prepare(MarketOrderRepository.User user, String roundId, String quoteId) {
        BigInteger roundKey = lookupId(roundId);
        BigInteger quoteKey = lookupId(quoteId);
        var quote = orders.quote(user, quoteKey).orElseThrow(MarketOrderService::quoteNotFound);
        var market = orders.lockMarket(roundKey).orElseThrow(MarketOrderService::quoteNotFound);
        var stock = orders.lockStock(quote.stockId(), roundKey).orElseThrow(MarketOrderService::quoteNotFound);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (quote.usedAt() != null) {
            throw conflict("QUOTE_ALREADY_USED", "이미 사용한 견적입니다.");
        }
        if ("SUSPENDED".equals(market.status())) {
            throw conflict("MARKET_SUSPENDED", "거래가 일시 중단됐습니다.");
        }
        if (!"OPEN".equals(market.status()) || market.openAt() == null || market.closeAt() == null
                || now.isBefore(market.openAt()) || !now.isBefore(market.closeAt())) {
            throw conflict("MARKET_CLOSED", "현재는 마켓 거래 시간이 아닙니다.");
        }
        if (!now.isBefore(quote.expiresAt())) {
            throw conflict("QUOTE_EXPIRED", "견적이 만료됐습니다.");
        }
        if (!stock.priceVersion().equals(quote.priceVersion())) {
            throw conflict("PRICE_CHANGED", "종목 가격이 바뀌었습니다. 다시 견적을 받아 주세요.");
        }

        boolean buy = "BUY".equals(quote.side());
        var position = orders.position(user, quote);
        var quantity = BigInteger.valueOf(quote.quantity());
        if (!buy && position.quantity().compareTo(quantity) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_HOLDINGS", "보유 수량이 부족합니다.");
        }
        long delta = buy ? -Math.addExact(quote.totalAmount(), quote.fee())
                : Math.subtractExact(quote.totalAmount(), quote.fee());
        long balance = Math.addExact(user.cashBalance(), delta);
        if (balance < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE", "보유 코인이 부족합니다.");
        }

        Long allocated = buy ? null : BigDecimal.valueOf(position.totalCost()).multiply(new BigDecimal(quantity))
                .divide(new BigDecimal(position.quantity()), 0, RoundingMode.HALF_UP).longValueExact();
        Long pnl = allocated == null ? null : Math.subtractExact(delta, allocated);
        long cost = buy ? Math.subtractExact(position.totalCost(), delta) : position.totalCost() - allocated;
        var holding = buy ? position.quantity().add(quantity) : position.quantity().subtract(quantity);

        var unitPrices = readFillPrices(quote);
        BigDecimal theoretical = stock.theoreticalPrice().max(BigDecimal.valueOf(stock.startingPrice()));
        BigDecimal rate = rules.tradeImpactRate(market.snapshot());
        for (int index = 0; index < quote.quantity(); index++) {
            theoretical = MarketTradePrice.afterShare(theoretical, stock.startingPrice(), rate, buy);
        }
        if (MarketTradePrice.displayed(theoretical) != quote.priceAfter()) {
            throw conflict("PRICE_CHANGED", "거래 가격이 바뀌었습니다. 다시 견적을 받아 주세요.");
        }

        Instant executedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!executedAt.isBefore(market.closeAt())) {
            throw conflict("MARKET_CLOSED", "현재는 마켓 거래 시간이 아닙니다.");
        }
        if (!executedAt.isBefore(quote.expiresAt())) {
            throw conflict("QUOTE_EXPIRED", "견적이 만료됐습니다.");
        }

        var order = new MarketOrderResponse(MarketQuoteRepository.newId(), roundId, stock.driverId(), quote.side(),
                quote.quantity(), unitPrices, quote.totalAmount(), quote.fee(), quote.average(), balance, holding,
                quote.priceAfter(), stock.priceVersion().add(BigInteger.ONE), executedAt);
        return new Execution(order, quoteKey, quote, allocated, pnl, buy, cost, delta, theoretical);
    }

    private List<Long> readFillPrices(MarketOrderRepository.Quote quote) {
        var values = json.readTree(quote.unitPrices());
        if (!values.isArray() || quote.quantity() <= 0 || values.size() != quote.quantity()
                || !List.of("BUY", "SELL").contains(quote.side())) {
            throw new IllegalStateException("저장된 견적의 단가 또는 주문 방향이 잘못됐습니다.");
        }
        var prices = new ArrayList<Long>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw new IllegalStateException("저장된 견적의 단가가 잘못됐습니다.");
            }
            prices.add(value.longValue());
        }
        return List.copyOf(prices);
    }

    private static BigInteger lookupId(String value) {
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw quoteNotFound();
        }
        var id = new BigInteger(value);
        if (id.bitLength() > 64) {
            throw quoteNotFound();
        }
        return id;
    }

    private static String normalizeKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw ApiException.invalidRequest("Idempotency-Key는 UUID여야 합니다.");
        }
        return UUID.fromString(key).toString();
    }

    private static byte[] fingerprint(String request) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static ApiException quoteNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "주문에 사용할 견적을 찾을 수 없습니다.");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record OrderRequest(String roundId, String quoteId) { }

    public record Result(int status, JsonNode body) { }
    private record Execution(MarketOrderResponse order, BigInteger quoteId, MarketOrderRepository.Quote quote,
                             Long allocated, Long pnl, boolean buy, long newCost, long delta, BigDecimal theoretical) { }
}
