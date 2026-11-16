package com.boxbox.market.trade.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketOrderHistoryResponse(List<Item> items, String nextCursor, boolean hasNext) {
    public record Item(String orderId, String roundId, String driverId, String type, BigInteger quantity,
                       BigInteger totalAmount, BigDecimal averageUnitPrice, BigInteger realizedPnl,
                       Instant executedAt) { }
}
