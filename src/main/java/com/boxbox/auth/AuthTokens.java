package com.boxbox.auth;

import com.boxbox.common.api.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
class AuthTokens {
    static final long ACCESS_SECONDS = 900;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();

    AuthTokens(Environment env, Clock clock) {
        this.clock = clock;
        byte[] secret;
        try { secret = Base64.getDecoder().decode(env.getProperty("boxbox.auth.jwt-secret", "")); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("AUTH_JWT_SECRET은 Base64 형식이어야 합니다."); }
        if (secret.length < 32) throw new IllegalArgumentException("AUTH_JWT_SECRET에 최소32바이트 난수의 Base64 값을 설정해 주세요.");
        var key = new SecretKeySpec(secret, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(jwt -> {
            Instant now = clock.instant();
            Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt(), notBefore = jwt.getNotBefore();
            boolean valid = "https://boxbox.local".equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                    && jwt.getAudience() != null && jwt.getAudience().contains("boxbox-api") && jwt.getSubject() != null
                    && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                    && issued != null && !issued.isAfter(now) && expires != null && expires.isAfter(now)
                    && expires.isAfter(issued) && Duration.between(issued, expires).getSeconds() <= ACCESS_SECONDS
                    && (notBefore == null || !notBefore.isAfter(now));
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
    }

    String access(long userId) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer("https://boxbox.local").audience(List.of("boxbox-api"))
                .subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plusSeconds(ACCESS_SECONDS))
                .id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    long verify(String token) {
        return verified(token).userId();
    }

    record VerifiedAccess(long userId, Instant expiresAt) {}
    VerifiedAccess verified(String token) {
        if (token == null || token.length() > 4096) throw ApiException.unauthorized();
        try {
            Jwt jwt = decoder.decode(token);
            return new VerifiedAccess(Long.parseLong(jwt.getSubject()), jwt.getExpiresAt());
        }
        catch (JwtException | IllegalArgumentException error) { throw ApiException.unauthorized(); }
    }

    String refresh() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean validRefresh(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
