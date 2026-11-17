package com.boxbox.market.trade;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.market.trade.dto.MarketOrderHistoryResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MarketOrderHistoryController {
    private final MarketOrderHistoryService service;

    @GetMapping("/api/me/orders")
    ApiResponse<MarketOrderHistoryResponse> orders(
            @RequestParam(name = "roundId", required = false) String roundId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") String size,
            HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal == null) {
            return ApiResponse.success(service.history(null, roundId, type, cursor, size));
        }
        if (!(principal instanceof AuthPrincipal member) || !member.isValid()) {
            throw ApiException.unauthorized();
        }
        return ApiResponse.success(service.history(member.getName(), roundId, type, cursor, size));
    }
}
