package com.boxbox.market.settlement;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.settlement.dto.MarketSettlementDetailResponse;
import com.boxbox.market.settlement.dto.MarketSettlementListResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketSettlementController {
    private final MarketSettlementService service;

    @GetMapping("/api/me/settlements")
    ApiResponse<MarketSettlementListResponse> settlements(
            @RequestParam(name = "season", required = false) String season,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") String size,
            HttpServletRequest request) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.list(loginId(request), season, cursor, size, meta.serverTime()), meta);
    }

    @GetMapping("/api/me/settlements/{roundId}")
    ApiResponse<MarketSettlementDetailResponse> detail(@PathVariable("roundId") String roundId,
                                                        HttpServletRequest request) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.detail(loginId(request), roundId, meta.serverTime()), meta);
    }

    private static String loginId(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal != null && (!(principal instanceof AuthPrincipal member) || !member.isValid())) {
            throw ApiException.unauthorized();
        }
        return principal == null ? null : principal.getName();
    }
}
