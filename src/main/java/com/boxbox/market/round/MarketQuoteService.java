package com.boxbox.market.round;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.dto.MarketQuoteResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
class MarketQuoteService {
    private final MarketRoundRepository rounds;
    private final MarketRoundService rules;
    private final MarketQuoteRepository quotes;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public MarketQuoteResponse create(String loginId, String roundId, JsonNode body, Instant now) {
        var request = QuoteRequest.parse(body);
        var roundKey = MarketStockService.stockLookupId(roundId);
        var driverKey = MarketStockService.stockLookupId(request.driverId());
        var detail = rounds.findById(roundKey).orElseThrow(MarketStockService::stockNotFound);
        var round = detail.round();
        var stock = quotes.findStock(roundKey, round.season(), driverKey).orElseThrow(MarketStockService::stockNotFound);
        var account = quotes.account(loginId, stock.id());
        if ("SUSPENDED".equals(round.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MARKET_SUSPENDED", "거래가 일시 중단됐습니다.");
        }
        if (!"OPEN".equals(round.status()) || round.openAt() == null || round.closeAt() == null
                || now.isBefore(round.openAt()) || !now.isBefore(round.closeAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "MARKET_CLOSED", "현재는 마켓 거래 시간이 아닙니다.");
        }
        if (!request.buy() && account.holdingQuantity().compareTo(BigInteger.valueOf(request.quantity())) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_HOLDINGS", "보유 수량이 부족합니다.");
        }

        var pricing = calculate(request, stock, account.cashBalance(), rules.tradeImpactRate(detail.ruleSnapshot()));
        BigDecimal quantity = BigDecimal.valueOf(request.quantity());
        BigDecimal rawTotal = BigDecimal.valueOf(pricing.total());
        BigDecimal average = rawTotal.divide(quantity, 8, RoundingMode.HALF_UP);
        Long pnl = request.buy() ? null : pricing.total() - BigDecimal.valueOf(account.totalCost()).multiply(quantity)
                .divide(new BigDecimal(account.holdingQuantity()), 0, RoundingMode.HALF_UP).longValueExact();
        BigDecimal pnlPerShare = pnl == null ? null : BigDecimal.valueOf(pnl).divide(quantity, 8, RoundingMode.HALF_UP);
        BigDecimal startingValue = BigDecimal.valueOf(stock.startingPrice()).multiply(quantity);
        BigDecimal changeRate = rawTotal.subtract(startingValue).movePointRight(2)
                .divide(startingValue, 8, RoundingMode.HALF_UP);
        Instant expiresAt = now.truncatedTo(ChronoUnit.MICROS).plusSeconds(5);
        for (int attempt = 0; attempt < 3; attempt++) {
            var quote = new MarketQuoteResponse(MarketQuoteRepository.newId(), roundId, request.driverId(),
                    request.buy() ? "BUY" : "SELL", request.quantity(), pricing.unitPrices(), pricing.total(), 0,
                    average, pricing.after(), stock.priceVersion(), expiresAt, account.cashBalance(),
                    account.holdingQuantity(), pnl, pnlPerShare, changeRate);
            try {
                quotes.save(account, stock, quote, now);
                return quote;
            } catch (DuplicateKeyException collision) {
                if (attempt == 2) {
                    throw collision;
                }
            }
        }
        throw new IllegalStateException("견적 ID 발급에 실패했습니다.");
    }

    private static Pricing calculate(QuoteRequest request, MarketQuoteRepository.Stock stock, long balance,
                                     BigDecimal impact) {
        if (stock.startingPrice() <= 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "종목 시작가를 확인해 주세요.");
        }
        long price = Math.max(stock.currentPrice(), stock.startingPrice());
        BigDecimal theoretical = stock.theoreticalPrice().max(BigDecimal.valueOf(stock.startingPrice()));
        if (request.buy() && request.quantity() > balance / price) {
            throw insufficientBalance();
        }
        var unitPrices = new ArrayList<Long>();
        long total = 0;
        try {
            for (int share = 0; share < request.quantity(); share++) {
                if (request.buy() && price > balance - total) {
                    throw insufficientBalance();
                }
                unitPrices.add(price);
                total = Math.addExact(total, price);
                theoretical = MarketTradePrice.afterShare(theoretical, stock.startingPrice(), impact, request.buy());
                price = MarketTradePrice.displayed(theoretical);
            }
        } catch (ArithmeticException overflow) {
            throw ApiException.invalidRequest("거래 금액이 지원 범위를 벗어났습니다.");
        }
        return new Pricing(List.copyOf(unitPrices), total, price);
    }

    private static ApiException insufficientBalance() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE", "보유 코인이 부족합니다.");
    }

    private record Pricing(List<Long> unitPrices, long total, long after) { }

    private record QuoteRequest(String driverId, boolean buy, int quantity) {
        static QuoteRequest parse(JsonNode body) {
            var driver = body.path("driverId");
            var side = body.path("side");
            var quantity = body.path("quantity");
            if (!driver.isString() || driver.stringValue().isBlank()
                    || !side.isString() || !List.of("BUY", "SELL").contains(side.stringValue())
                    || !quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue() <= 0) {
                throw ApiException.invalidRequest("driverId, side(BUY/SELL), 양의 정수 quantity를 확인해 주세요.");
            }
            return new QuoteRequest(driver.stringValue(), "BUY".equals(side.stringValue()), quantity.intValue());
        }
    }
}
