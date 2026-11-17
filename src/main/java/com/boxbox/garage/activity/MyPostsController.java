package com.boxbox.garage.activity;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.garage.activity.dto.MyPostsResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class MyPostsController {
    private final MyPostsService service;

    @GetMapping("/api/me/posts")
    ApiResponse<MyPostsResponse> list(@RequestParam Map<String, String> params, HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) throw ApiException.unauthorized();
        return ApiResponse.success(service.list(principal.getName(), params));
    }
}
