package com.boxbox.market.round;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MarketTradePrice {
    private MarketTradePrice() { }

    static BigDecimal afterShare(BigDecimal theoreticalPrice, long startingPrice, BigDecimal percentage, boolean buy) {
        BigDecimal impact = percentage.movePointLeft(2);
        BigDecimal multiplier = buy ? BigDecimal.ONE.add(impact) : BigDecimal.ONE.subtract(impact);
        return theoreticalPrice.multiply(multiplier).max(BigDecimal.valueOf(startingPrice))
                .setScale(8, RoundingMode.HALF_UP);
    }

    static long displayed(BigDecimal price) {
        return price.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
