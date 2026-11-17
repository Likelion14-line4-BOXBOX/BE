package com.boxbox.market.settlement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public record MarketSettlementPrice(int positionScore, int resultBonus, int finalScore, BigInteger price) {
    private static final int MIN_SCORE = -30;

    public static MarketSettlementPrice calculate(
            BigInteger startingPrice, int baseRank, int finishRank, int resultBonus
    ) {
        // Include the better rank and exclude the worse rank in the sum of crossed ranks.
        int positionScore = (baseRank - finishRank) * (baseRank + finishRank - 1) / 2;
        return fromScores(startingPrice, positionScore, resultBonus);
    }

    public static MarketSettlementPrice retirement(BigInteger startingPrice) {
        return fromScores(startingPrice, MIN_SCORE, 0);
    }

    private static MarketSettlementPrice fromScores(BigInteger startingPrice, int positionScore, int resultBonus) {
        int finalScore = (int) Math.clamp((long) positionScore + resultBonus, MIN_SCORE, 40);
        BigInteger price = new BigDecimal(startingPrice).multiply(BigDecimal.valueOf(100 + finalScore))
                .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
        return new MarketSettlementPrice(positionScore, resultBonus, finalScore, price);
    }
}
