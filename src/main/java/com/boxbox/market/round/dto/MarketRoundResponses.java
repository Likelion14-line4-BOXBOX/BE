package com.boxbox.market.round.dto;

import java.math.BigInteger;
import java.time.Instant;

public final class MarketRoundResponses {
    private MarketRoundResponses() { }

    public record Current(Round round) { }

    public record Round(String roundId, String raceId, int season, int roundNumber, String raceName,
                        String status, Instant openAt, Instant closeAt, Instant publishAt,
                        Instant rankingPublishedAt, String ruleVersion, BigInteger statusVersion,
                        String chatRoomId) { }
}
