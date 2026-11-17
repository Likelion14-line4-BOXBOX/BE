package com.boxbox.market.portfolio;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.portfolio.dto.MarketPortfolioResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketPortfolioController {
    private final MarketPortfolioService service;

    @GetMapping("/api/me/portfolio")
    ApiResponse<MarketPortfolioResponse> portfolio(@RequestParam(name = "roundId", required = false) String roundId,
                                                  HttpServletRequest request) {
        String loginId = null;
        if (request.getUserPrincipal() instanceof AuthPrincipal principal) {
            if (!principal.isValid()) {
                throw ApiException.unauthorized();
            }
            loginId = principal.getName();
        } else if (request.getUserPrincipal() != null) {
            throw ApiException.unauthorized();
        }
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.portfolio(loginId, roundId, meta.serverTime()), meta);
    }
}
