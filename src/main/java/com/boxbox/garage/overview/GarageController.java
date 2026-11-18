package com.boxbox.garage.overview;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.garage.overview.dto.GarageResponse;
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
class GarageController {
    private final GarageService service;

    @GetMapping("/api/users/{userId}/garage")
    ApiResponse<GarageResponse> get(@PathVariable String userId, @RequestParam Map<String, String> params, HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) throw ApiException.unauthorized();
        return ApiResponse.success(service.get(principal.getName(), userId, params));
    }
}
