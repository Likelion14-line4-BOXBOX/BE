package com.boxbox.auth;

import com.boxbox.common.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AuthService {
    private final AuthRepository users;
    private final PasswordEncoder passwords;
    private final AuthTokens tokens;
    private final AuthSettings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    record SignupResponse(String userId, String loginId, String nickname) {}
    record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
    record Tokens(TokenResponse response, String refresh, Instant expires) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }
    private record RefreshResult(Tokens tokens, RefreshFailure failure) {}
    static final class RefreshFailure extends ApiException {
        RefreshFailure(String code) { super(HttpStatus.UNAUTHORIZED, code, "다시 로그인해 주세요."); }
    }

    AuthService(AuthRepository users, PasswordEncoder passwords, AuthTokens tokens,
                AuthSettings settings, Clock clock, PlatformTransactionManager manager) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        dummyHash = passwords.encode(tokens.refresh());
    }

    SignupResponse signup(AuthInput.Signup input) {
        String hash = passwords.encode(input.password());
        return transaction.execute(status -> {
            if (users.byLogin(input.loginId()).isPresent()) throw duplicateLogin();
            if (users.nicknameExists(input.nickname())) {
                // A concurrent signup may have committed after the first login ID lookup.
                if (users.byLogin(input.loginId()).isPresent()) throw duplicateLogin();
                throw duplicateNickname();
            }
            try {
                long id = users.createUser(input, hash, settings.initialCoins, clock.instant());
                return new SignupResponse(Long.toString(id), input.loginId(), input.nickname());
            } catch (DuplicateKeyException conflict) {
                if (users.byLogin(input.loginId()).isPresent()) throw duplicateLogin();
                if (users.nicknameExists(input.nickname())) throw duplicateNickname();
                throw conflict;
            }
        });
    }

    Tokens login(AuthInput.Login input) {
        var candidate = users.byLogin(input.loginId());
        boolean matches;
        try { matches = passwords.matches(input.password(), candidate.map(AuthRepository.User::passwordHash).orElse(dummyHash)); }
        catch (IllegalArgumentException unsupportedHash) { matches = false; }
        if (!matches || candidate.isEmpty() || !candidate.get().active()) throw loginFailed();
        var expected = candidate.get();
        return transaction.execute(status -> {
            var current = users.byId(expected.id(), true).orElseThrow(AuthService::loginFailed);
            if (!current.active() || !current.passwordHash().equals(expected.passwordHash())) throw loginFailed();
            Instant now = clock.instant();
            return issue(current.id(), UUID.randomUUID().toString(), now.plus(settings.refreshLifetime), now);
        });
    }

    Tokens demoLogin() {
        return transaction.execute(status -> {
            var candidate = users.byLogin(settings.demoLoginId).orElseThrow(AuthService::demoUnavailable);
            var current = users.byId(candidate.id(), true).orElseThrow(AuthService::demoUnavailable);
            if (!current.active()) throw demoUnavailable();
            Instant now = clock.instant();
            return issue(current.id(), UUID.randomUUID().toString(), now.plus(settings.refreshLifetime), now);
        });
    }

    private static ApiException demoUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_ACCOUNT_UNAVAILABLE",
                "체험 계정을 준비 중입니다. 잠시 후 다시 시도해 주세요.");
    }

    Tokens refresh(String raw) {
        if (!AuthTokens.validRefresh(raw)) throw new RefreshFailure("REFRESH_TOKEN_INVALID");
        byte[] hash = AuthTokens.hash(raw);
        var known = users.session(hash, false).orElseThrow(() -> new RefreshFailure("REFRESH_TOKEN_INVALID"));
        RefreshResult result = transaction.execute(status -> {
            // 다른 기능과 같은 회원→세션 잠금 순서로 회전/재사용/로그아웃을 직렬화한다.
            var user = users.byId(known.userId(), true);
            var stored = users.session(hash, true).orElse(null);
            if (stored == null || user.isEmpty()) return rejected("REFRESH_TOKEN_INVALID");
            Instant now = clock.instant();
            if (stored.rotated()) {
                users.revokeFamily(stored.userId(), stored.family(), now);
                return rejected("REFRESH_TOKEN_REUSED");
            }
            if (stored.revoked() || !user.get().active()) {
                users.revokeFamily(stored.userId(), stored.family(), now);
                return rejected("REFRESH_TOKEN_INVALID");
            }
            if (!stored.expiresAt().isAfter(now)) {
                users.revokeFamily(stored.userId(), stored.family(), now);
                return rejected("REFRESH_TOKEN_EXPIRED");
            }
            String replacement = tokens.refresh();
            long id = users.createSession(stored.userId(), stored.family(), AuthTokens.hash(replacement), stored.expiresAt(), now);
            users.rotate(stored.id(), id, now);
            return new RefreshResult(pair(stored.userId(), replacement, stored.expiresAt()), null);
        });
        // 폐기 트랜잭션을 먼저 커밋해야 401 예외로 재사용 탐지 결과가 롤백되지 않는다.
        if (result.failure() != null) throw result.failure();
        return result.tokens();
    }

    void logout(String raw) {
        if (!AuthTokens.validRefresh(raw)) return;
        var known = users.session(AuthTokens.hash(raw), false);
        if (known.isEmpty()) return;
        transaction.executeWithoutResult(status -> {
            if (users.byId(known.get().userId(), true).isPresent())
                users.revokeFamily(known.get().userId(), known.get().family(), clock.instant());
        });
    }

    AuthPrincipal principal(String access) {
        var verified = tokens.verified(access);
        return users.byId(verified.userId(), false).filter(AuthRepository.User::readable)
                .map(user -> new AuthPrincipal(user.loginId(), verified.expiresAt(), clock)).orElseThrow(ApiException::unauthorized);
    }

    private Tokens issue(long userId, String family, Instant expires, Instant now) {
        String refresh = tokens.refresh();
        users.createSession(userId, family, AuthTokens.hash(refresh), expires, now);
        return pair(userId, refresh, expires);
    }
    private Tokens pair(long userId, String raw, Instant expires) {
        return new Tokens(new TokenResponse(tokens.access(userId), "Bearer", tokens.accessSeconds(userId)), raw, expires);
    }
    private static RefreshResult rejected(String code) { return new RefreshResult(null, new RefreshFailure(code)); }
    private static ApiException loginFailed() { return new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "로그인 정보를 확인해 주세요."); }
    private static ApiException duplicateLogin() { return new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_ALREADY_EXISTS", "이미 사용 중인 아이디입니다."); }
    private static ApiException duplicateNickname() { return new ApiException(HttpStatus.CONFLICT, "NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다."); }
}
