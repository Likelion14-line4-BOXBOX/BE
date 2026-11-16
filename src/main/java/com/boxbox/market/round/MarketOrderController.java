package com.boxbox.market.round;

import com.boxbox.auth.AuthPrincipal;
import com.boxbox.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
class MarketOrderController {
    private final MarketOrderService service;

    @PostMapping(path = "/api/market/rounds/{roundId}/orders", consumes = "application/json")
    ResponseEntity<JsonNode> execute(@PathVariable("roundId") String roundId,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                     @RequestBody JsonNode body, HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof AuthPrincipal principal) || !principal.isValid()) {
            throw ApiException.unauthorized();
        }
        var result = service.execute(principal.getName(), roundId, key, body);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
