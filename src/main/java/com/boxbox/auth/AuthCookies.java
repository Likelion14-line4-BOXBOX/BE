package com.boxbox.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AuthCookies {
    private final AuthSettings settings;
    private final Clock clock;

    String read(HttpServletRequest request) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if ("refreshToken".equals(cookie.getName())) {
            if (value != null) throw new AuthService.RefreshFailure("REFRESH_TOKEN_INVALID");
            value = cookie.getValue();
        }
        return value;
    }
    String issue(String raw, Instant expires) {
        return cookie(raw, Duration.ofSeconds(Math.max(0, Duration.between(clock.instant(), expires).getSeconds())));
    }
    String clear() { return cookie("", Duration.ZERO); }
    private String cookie(String value, Duration age) {
        return ResponseCookie.from("refreshToken", value).httpOnly(true).secure(true).path("/api/auth")
                .sameSite(settings.sameSite).maxAge(age).build().toString();
    }
}
