package com.boxbox.auth;

import com.boxbox.common.api.ApiErrorResponse;
import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {
    private final AuthService service;
    private final AuthCookies cookies;
    private final AuthRateLimiter rate;
    private final AuthSettings settings;

    @PostMapping("/signup")
    ResponseEntity<ApiResponse<AuthService.SignupResponse>> signup(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        rate.acquire("signup-ip", request.getRemoteAddr(), settings.signupLimit);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResponse.success(service.signup(AuthInput.signup(body))));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<AuthService.TokenResponse>> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        rate.acquire("login-ip", request.getRemoteAddr(), settings.loginIpLimit);
        var input = AuthInput.login(body);
        rate.acquire("login-account", input.loginId(), settings.loginAccountLimit);
        return issued(service.login(input));
    }

    @PostMapping("/demo")
    ResponseEntity<ApiResponse<AuthService.TokenResponse>> demo(
            HttpServletRequest request, @RequestBody(required = false) String body) {
        noBody(request, body);
        rate.acquire("demo-ip", request.getRemoteAddr(), settings.loginIpLimit);
        return issued(service.demoLogin());
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<AuthService.TokenResponse>> refresh(HttpServletRequest request, @RequestBody(required = false) String body) {
        noBody(request, body);
        rate.acquire("refresh-ip", request.getRemoteAddr(), settings.refreshLimit);
        return issued(service.refresh(cookies.read(request)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, @RequestBody(required = false) String body) {
        noBody(request, body);
        service.logout(cookies.read(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @ExceptionHandler(AuthService.RefreshFailure.class)
    ResponseEntity<ApiErrorResponse> refreshFailure(AuthService.RefreshFailure error) {
        return ResponseEntity.status(error.getStatus()).header(HttpHeaders.SET_COOKIE, cookies.clear())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiErrorResponse.of(error.getCode(), error.getMessage()));
    }

    private ResponseEntity<ApiResponse<AuthService.TokenResponse>> issued(AuthService.Tokens tokens) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.issue(tokens.refresh(), tokens.expires()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.success(tokens.response()));
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw ApiException.invalidRequest("인증 API에는 조회 조건을 지정하지 않습니다.");
    }
    private static void noBody(HttpServletRequest request, String body) {
        noQuery(request);
        if (body != null && !body.isEmpty()) throw ApiException.invalidRequest("요청 본문이 없는 API입니다.");
    }
}
