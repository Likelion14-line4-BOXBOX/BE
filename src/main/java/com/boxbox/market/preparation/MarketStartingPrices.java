package com.boxbox.market.preparation;

import java.util.List;
import java.util.OptionalInt;

final class MarketStartingPrices {
    // Index zero is P1 in the race market rules.
    private static final List<Integer> BY_RANK = List.of(
            50_000, 42_000, 36_000, 31_000, 27_000, 24_000, 21_500, 19_500, 17_500, 16_000,
            14_500, 13_000, 12_000, 11_000, 10_000, 9_000, 8_000, 7_000, 6_000, 5_000, 4_000, 3_000
    );

    private MarketStartingPrices() {}

    static OptionalInt forRank(int rank) {
        return rank >= 1 && rank <= BY_RANK.size()
                ? OptionalInt.of(BY_RANK.get(rank - 1))
                : OptionalInt.empty();
    }
}
