package com.boxbox.market.round.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

public final class MarketRoundResponses {
    private MarketRoundResponses() { }

    public record Current(Round round) { }

    public record Round(String roundId, String raceId, int season, int roundNumber, String raceName,
                        String status, Instant openAt, Instant closeAt, Instant publishAt,
                        Instant rankingPublishedAt, String ruleVersion, BigInteger statusVersion,
                        String chatRoomId) { }

    public record Detail(String roundId, String raceId, int season, int roundNumber, String raceName,
                         String status, Instant openAt, Instant closeAt, Instant publishAt,
                         Instant rankingPublishedAt, String ruleVersion, BigInteger statusVersion,
                         String chatRoomId, BigDecimal tradeImpactRate, String settlementBase,
                         String settlementStatus) {
        public Detail(Round round, BigDecimal tradeImpactRate, String settlementBase, String settlementStatus) {
            this(round.roundId(), round.raceId(), round.season(), round.roundNumber(), round.raceName(),
                    round.status(), round.openAt(), round.closeAt(), round.publishAt(), round.rankingPublishedAt(),
                    round.ruleVersion(), round.statusVersion(), round.chatRoomId(), tradeImpactRate, settlementBase,
                    settlementStatus);
        }
    }
}
