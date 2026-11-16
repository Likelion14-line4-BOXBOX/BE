package com.boxbox.market.round.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketQuoteResponse(String quoteId, String roundId, String driverId, String side, int quantity,
                                  List<Long> unitPrices, long totalAmount, long fee, BigDecimal averageUnitPrice,
                                  long priceAfter, BigInteger priceVersion, Instant expiresAt, long cashBalance,
                                  BigInteger holdingQuantity, Long estimatedRealizedPnl, BigDecimal estimatedPnlPerShare,
                                  BigDecimal changeRateFromStartingPrice) { }
