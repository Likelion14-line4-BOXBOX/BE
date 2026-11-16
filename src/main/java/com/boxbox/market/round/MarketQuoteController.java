package com.boxbox.market.round;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.round.dto.MarketQuoteResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
class MarketQuoteController {
    private final MarketQuoteService service;

    @PostMapping(path = "/api/market/rounds/{roundId}/quotes", consumes = "application/json")
    ApiResponse<MarketQuoteResponse> quote(@PathVariable("roundId") String roundId,
                                          @RequestBody JsonNode body, HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof AuthPrincipal principal) || !principal.isValid()) {
            throw ApiException.unauthorized();
        }
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.create(principal.getName(), roundId, body, meta.serverTime()), meta);
    }
}
