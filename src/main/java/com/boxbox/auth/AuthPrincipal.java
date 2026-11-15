package com.boxbox.auth;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;

/** 검증된 access token의 회원명과 유효 기간만 전달한다. 토큰 원문은 보관하지 않는다. */
public final class AuthPrincipal implements Principal {
    private final String loginId;
    private final Instant expiresAt;
    private final Clock clock;

    AuthPrincipal(String loginId, Instant expiresAt, Clock clock) {
        this.loginId = loginId;
        this.expiresAt = expiresAt;
        this.clock = clock;
    }
    @Override public String getName() { return loginId; }
    public boolean isValid() { return expiresAt.isAfter(clock.instant()); }
}
