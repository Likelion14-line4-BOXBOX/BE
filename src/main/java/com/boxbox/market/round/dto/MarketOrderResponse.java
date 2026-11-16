package com.boxbox.market.round.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketOrderResponse(String orderId, String roundId, String driverId, String type, int quantity,
                                  List<Long> unitPrices, long totalAmount, long fee, BigDecimal averageUnitPrice,
                                  long cashBalance, BigInteger holdingQuantity, long currentPrice,
                                  BigInteger priceVersion, Instant executedAt) { }
