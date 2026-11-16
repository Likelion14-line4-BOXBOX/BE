package com.boxbox.market.settlement;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.settlement.dto.MarketSettlementListResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
        var principal = request.getUserPrincipal();
        if (principal != null && (!(principal instanceof AuthPrincipal member) || !member.isValid())) {
            throw ApiException.unauthorized();
        }
        var meta = ApiResponse.Meta.create();
        String loginId = principal == null ? null : principal.getName();
        return new ApiResponse<>(service.list(loginId, season, cursor, size, meta.serverTime()), meta);
    }
}
