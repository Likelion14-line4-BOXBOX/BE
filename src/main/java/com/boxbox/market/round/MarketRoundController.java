package com.boxbox.market.round;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.round.dto.MarketRoundResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketRoundController {
    private final MarketRoundService service;

    @GetMapping("/api/market/rounds/current")
    ApiResponse<MarketRoundResponses.Current> current() {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.current(meta.serverTime()), meta);
    }

    @GetMapping("/api/market/rounds/{roundId}")
    ApiResponse<MarketRoundResponses.Detail> detail(@PathVariable("roundId") String roundId) {
        return ApiResponse.success(service.detail(roundId));
    }
}
