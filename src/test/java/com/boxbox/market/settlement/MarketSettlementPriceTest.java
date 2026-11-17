package com.boxbox.market.settlement;

import java.math.BigInteger;
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

    private static void assertPrice(int start, int base, int finish, int bonus, int position, int score, int price) {
        var result = MarketSettlementPrice.calculate(BigInteger.valueOf(start), base, finish, bonus);
        assertEquals(new MarketSettlementPrice(position, bonus, score, BigInteger.valueOf(price)), result);
    }
}
