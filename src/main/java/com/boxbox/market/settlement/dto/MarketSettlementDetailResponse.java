package com.boxbox.market.settlement.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketSettlementDetailResponse(
        String roundId, String status, String ruleVersion, long settlementVersion,
        BigInteger roundStartAssets, BigInteger endingAssets, BigInteger roundPnl,
        BigDecimal returnRate, Long rank, Long seasonRank, BigDecimal seasonReturnRate,
        BigInteger settlementProceeds, List<Item> items, Instant publishedAt) {

    public record Item(String driverId, String driverName, int baseRank, Integer finishRank,
                       int positionScore, int resultBonus, int finalScore, BigInteger startingPrice,
                       BigInteger settlementPrice, BigInteger quantity, BigInteger totalCost,
                       BigInteger settlementProceeds, BigInteger settlementPnl) { }
}
