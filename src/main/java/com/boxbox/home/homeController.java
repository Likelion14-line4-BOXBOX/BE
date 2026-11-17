package com.boxbox.home;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardViewerResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class HomeController {
    private final BoardViewerResolver viewers;
    private final HomeService service;

    @GetMapping("/api/home")
    ApiResponse<HomeResponse> home(HttpServletRequest request) {
        var meta = ApiResponse.Meta.create();
        return new ApiResponse<>(service.home(viewers.resolveLoginId(request), meta.serverTime()), meta);
    }
}
