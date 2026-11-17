package com.boxbox.market.settlement.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketSettlementListResponse(List<Item> items, String nextCursor, boolean hasNext) {
    public record Item(String roundId, String raceName, int roundNumber, String status, BigInteger roundStartAssets,
                       BigInteger endingAssets, BigInteger roundPnl, BigDecimal returnRate, Long rank,
                       BigInteger settlementProceeds, Instant publishedAt) { }
}
