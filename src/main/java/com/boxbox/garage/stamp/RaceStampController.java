package com.boxbox.garage.stamp;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.garage.stamp.dto.RaceStampsResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class RaceStampController {
    private final RaceStampService service;

    @GetMapping("/api/users/{userId}/race-stamps")
    ApiResponse<RaceStampsResponse> list(@PathVariable String userId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) throw ApiException.unauthorized();
        return ApiResponse.success(service.list(principal.getName(), userId, params));
    }
}
