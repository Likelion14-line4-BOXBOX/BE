package com.boxbox.market.round;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.dto.MarketRoundResponses;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class MarketRoundService {
    private static final BigDecimal DEFAULT_TRADE_IMPACT_RATE = new BigDecimal("0.01");
    private static final BigInteger MAX_ROUND_ID = new BigInteger("18446744073709551615");

    private final MarketRoundRepository repository;
    private final JsonMapper json;
    private final int activeSeason;

    MarketRoundService(MarketRoundRepository repository, JsonMapper json,
                       @Value("${boxbox.market.active-season:2026}") int activeSeason) {
        this.repository = repository;
        this.json = json;
        this.activeSeason = activeSeason;
    }

    MarketRoundResponses.Current current(Instant now) {
        var round = repository.current(activeSeason, now)
                .map(MarketRoundService::toRound)
                .orElse(null);
        return new MarketRoundResponses.Current(round);
    }

    MarketRoundResponses.Detail detail(String roundId) {
        BigInteger id = parseRoundId(roundId);
        var detail = repository.findById(id).orElseThrow(MarketRoundService::roundNotFound);
        var rate = json.readTree(detail.ruleSnapshot()).path("tradeImpactRate");
        if (!rate.isMissingNode() && (!rate.isNumber() || rate.decimalValue().signum() < 0)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                    "라운드 거래 규칙을 읽을 수 없습니다.");
        }
        BigDecimal tradeImpactRate = rate.isMissingNode() ? DEFAULT_TRADE_IMPACT_RATE : rate.decimalValue();
        return new MarketRoundResponses.Detail(toRound(detail.round()), tradeImpactRate, "STARTING_PRICE",
                detail.settlementStatus());
    }

    static BigInteger parseRoundId(String roundId) {
        if (!roundId.matches("[1-9][0-9]{0,19}")) {
            throw roundNotFound();
        }
        BigInteger id = new BigInteger(roundId);
        if (id.compareTo(MAX_ROUND_ID) > 0) {
            throw roundNotFound();
        }

        return id;
    }

    static ApiException roundNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ROUND_NOT_FOUND", "마켓 라운드를 찾을 수 없습니다.");
    }

    private static MarketRoundResponses.Round toRound(MarketRoundRepository.RoundRow row) {
        return new MarketRoundResponses.Round(row.id(), row.raceId(), row.season(), row.roundNumber(),
                row.raceName(), row.status(), row.openAt(), row.closeAt(), row.publishAt(),
                row.rankingPublishedAt(), row.ruleVersion(), row.statusVersion(), row.chatRoomId());
    }
}
