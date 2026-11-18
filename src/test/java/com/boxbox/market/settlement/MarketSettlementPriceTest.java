package com.boxbox.market.settlement;

import com.boxbox.openf1.OpenF1Client.SessionResult;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketSettlementPriceTest {
    @Test
    void calculatesPricesFromMarketRuleExamples() {
        assertPrice(50_000, 1, 1, 8, 0, 8, 54_000);
        assertPrice(10_000, 15, 11, 0, 50, 40, 14_000);
        assertPrice(24_000, 6, 9, 1, -21, -20, 19_200);
        assertPrice(50_000, 1, 22, 0, -231, -30, 35_000);
    }

    @Test
    void retirementUsesOfficialRankOrLastPlaceWithoutBonus() {
        var startingPrice = BigInteger.valueOf(100);
        var stock = new MarketSettlementResultService.Stock(
                BigInteger.ONE, "1", "Driver", 23, 2, startingPrice);
        var winner = new SessionResult(1, 1, false, false, false);
        var third = new SessionResult(3, 3, false, false, false);

        var classified = MarketSettlementResultService.match(List.of(stock),
                List.of(winner, new SessionResult(23, 2, true, false, false), third))
                .orElseThrow().getFirst();
        assertEquals(2, classified.finishRank());
        assertEquals(new MarketSettlementPrice(0, 0, 0, startingPrice), classified.settlementPrice(6));

        var unclassified = MarketSettlementResultService.match(List.of(stock),
                List.of(winner, new SessionResult(23, null, true, false, false), third))
                .orElseThrow().getFirst();
        assertEquals(3, unclassified.finishRank());
        assertEquals(new MarketSettlementPrice(-2, 0, -2, BigInteger.valueOf(98)),
                unclassified.settlementPrice(5));
    }

    private static void assertPrice(int start, int base, int finish, int bonus, int position, int score, int price) {
        var result = MarketSettlementPrice.calculate(BigInteger.valueOf(start), base, finish, bonus);
        assertEquals(new MarketSettlementPrice(position, bonus, score, BigInteger.valueOf(price)), result);
    }
}
