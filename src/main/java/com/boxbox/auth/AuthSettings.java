package com.boxbox.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class AuthSettings {
    final Set<String> origins;
    final String sameSite;
    final Duration refreshLifetime;
    final long initialCoins;
    final int signupLimit;
    final int loginIpLimit;
    final int loginAccountLimit;
    final int refreshLimit;
    final String demoLoginId;

    AuthSettings(Environment env) {
        origins = Arrays.stream(env.getProperty("boxbox.auth.allowed-origins", "http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000,http://127.0.0.1:3000")
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("인증 Origin은 경로 없는 정확한 http(s) 출처여야 합니다.");
            }
        }
        sameSite = env.getProperty("boxbox.auth.cookie-same-site", "Lax");
        if (!Set.of("Lax", "Strict", "None").contains(sameSite)) throw new IllegalArgumentException("잘못된 SameSite 설정입니다.");
        refreshLifetime = Duration.parse(env.getProperty("boxbox.auth.refresh-lifetime", "P14D"));
        if (refreshLifetime.isNegative() || refreshLifetime.isZero() || refreshLifetime.compareTo(Duration.ofDays(90)) > 0)
            throw new IllegalArgumentException("refresh 유효 기간은 0초 초과 90일 이하여야 합니다.");
        initialCoins = env.getProperty("boxbox.auth.initial-coins", Long.class, 1_000_000L);
        if (initialCoins <= 0) throw new IllegalArgumentException("최초 지급액은 양수여야 합니다.");
        demoLoginId = env.getProperty("boxbox.auth.demo-login-id", "boxbox5").trim();
        if (!demoLoginId.matches("[a-z0-9_]{4,20}"))
            throw new IllegalArgumentException("체험 계정 아이디를 확인해 주세요.");
        signupLimit = positive(env, "signup", 10);
        loginIpLimit = positive(env, "login-ip", 30);
        loginAccountLimit = positive(env, "login-account", 10);
        refreshLimit = positive(env, "refresh", 60);
    }

    private int positive(Environment env, String key, int fallback) {
        int limit = env.getProperty("boxbox.auth.rate." + key, Integer.class, fallback);
        if (limit < 1) throw new IllegalArgumentException("인증 요청 제한은 양수여야 합니다.");
        return limit;
    }
}
