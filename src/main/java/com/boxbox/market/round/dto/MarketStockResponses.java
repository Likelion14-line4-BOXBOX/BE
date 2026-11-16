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

    public record Detail(Driver driver, int baseRank, BigInteger startingPrice, BigInteger currentPrice,
                         BigInteger changeAmount, BigDecimal changeRate, BigInteger priceVersion,
                         boolean tradable, Instant updatedAt, List<SettlementScenario> settlementScenarios) {
        public Detail(Item item, List<SettlementScenario> settlementScenarios) {
            this(item.driver(), item.baseRank(), item.startingPrice(), item.currentPrice(), item.changeAmount(),
                    item.changeRate(), item.priceVersion(), item.tradable(), item.updatedAt(), settlementScenarios);
        }
    }

    public record SettlementScenario(Integer finishRank, int positionScore, int resultBonus, int finalScore,
                                     BigInteger settlementPrice) { }
}
