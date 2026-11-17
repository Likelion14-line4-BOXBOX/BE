package com.boxbox.market.portfolio.dto;

import com.boxbox.market.stock.dto.MarketStockResponses.Driver;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record MarketPortfolioResponse(String roundId, BigInteger cashBalance, BigInteger stockValue,
                                      BigInteger totalAssets, BigInteger roundStartAssets, BigInteger realizedPnl,
                                      BigInteger unrealizedPnl, BigDecimal returnRate, BigInteger seasonStartAssets,
                                      BigDecimal seasonReturnRate, Long seasonRank, List<Position> positions,
                                      Instant snapshotAt) {
    public record Position(Driver driver, BigInteger quantity, BigInteger totalCost, BigDecimal averageBuyPrice,
                           BigInteger currentPrice, BigInteger valuation, int baseRank, BigDecimal returnRate,
                           BigInteger unrealizedPnl) { }
}
