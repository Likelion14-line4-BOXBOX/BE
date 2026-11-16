package com.boxbox.market.round;

import com.boxbox.market.round.dto.MarketRoundResponses;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class MarketRoundService {
    private final MarketRoundRepository repository;
    private final int activeSeason;

    MarketRoundService(MarketRoundRepository repository,
                       @Value("${boxbox.market.active-season:2026}") int activeSeason) {
        this.repository = repository;
        this.activeSeason = activeSeason;
    }

    MarketRoundResponses.Current current(Instant now) {
        var round = repository.current(activeSeason, now)
                .map(row -> new MarketRoundResponses.Round(row.id(), row.raceId(), row.season(),
                        row.roundNumber(), row.raceName(), row.status(), row.openAt(), row.closeAt(),
                        row.publishAt(), row.rankingPublishedAt(), row.ruleVersion(), row.statusVersion(),
                        row.chatRoomId()))
                .orElse(null);
        return new MarketRoundResponses.Current(round);
    }
}
