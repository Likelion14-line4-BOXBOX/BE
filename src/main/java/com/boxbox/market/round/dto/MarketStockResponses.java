package com.boxbox.market.round.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public final class MarketStockResponses {
    private MarketStockResponses() { }

    public record Page(List<Item> items, String nextCursor, boolean hasNext) { }

    public record Driver(String driverId, String name, int number, String teamId, String teamName) { }

    public record Item(Driver driver, int baseRank, BigInteger startingPrice, BigInteger currentPrice,
                       BigInteger changeAmount, BigDecimal changeRate, BigInteger priceVersion,
                       boolean tradable, Instant updatedAt, BigInteger totalBuyQuantity) { }
}
