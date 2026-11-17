package com.boxbox.market.ranking;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardViewerResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketRankingController {
    private final MarketRankingService rankings;
    private final BoardViewerResolver viewers;

    @GetMapping("/api/rankings")
    ApiResponse<MarketRankingService.Page> rankings(
            @RequestParam(defaultValue = "ASSET") String type,
            @RequestParam(defaultValue = "ROUND") String scope,
            @RequestParam(required = false) Integer season,
            @RequestParam(required = false) String roundId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(rankings.list(type, scope, season, roundId, cursor, size,
                viewers.resolveLoginId(request), meta.serverTime()), meta);
    }
}
