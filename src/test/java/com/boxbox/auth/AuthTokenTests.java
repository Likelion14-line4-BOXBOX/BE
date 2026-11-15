package com.boxbox.auth;

import static org.assertj.core.api.Assertions.*;
import com.boxbox.common.api.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class AuthTokenTests {
    static final String SECRET = "VGhpcy1pcy1hLXRlc3Qtb25seS1zaWduaW5nLWtleS0zMi1ieXRlcw==";
    static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    final AuthTokens tokens = new AuthTokens(new MockEnvironment().withProperty("boxbox.auth.jwt-secret", SECRET), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void generatedAccessIsVerifiableAndRefreshHasRandom256BitValue() {
        assertThat(tokens.verify(tokens.access(42))).isEqualTo(42);
        assertThat(tokens.refresh()).hasSize(43).isNotEqualTo(tokens.refresh());
        assertThat(AuthTokens.hash(tokens.refresh())).hasSize(32);
    }
    @Test void principalCarriesExpiryWithoutRetainingTokenText() {
        var clock = new AuthApiTests.MutableClock();
        var principal = new AuthPrincipal("member", clock.instant().plusSeconds(1), clock);
        assertThat(principal.getName()).isEqualTo("member");
        assertThat(principal.isValid()).isTrue();
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(principal.isValid()).isFalse();
    }
    @Test void rejectsTamperedSignatureAndUnsignedToken() {
        String[] pieces = tokens.access(42).split("\\.");
        pieces[2] = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
        assertThatThrownBy(() -> tokens.verify(String.join(".", pieces))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.verify("eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiJ9.")).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "subject", "zero", "future", "expired", "no-exp", "no-iat", "no-aud", "no-sub", "no-iss", "long-life", "not-before"})
    void rejectsSignedTokenWithInvalidRequiredClaims(String problem) {
        var builder = JwtClaimsSet.builder().issuer("https://boxbox.local").audience(List.of("boxbox-api")).subject("42")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(900));
        switch (problem) {
            case "issuer" -> builder.issuer("https://other.test");
            case "audience" -> builder.audience(List.of("other"));
            case "subject" -> builder.subject("viewer");
            case "zero" -> builder.subject("0");
            case "future" -> builder.issuedAt(NOW.plusSeconds(1));
            case "expired" -> builder.issuedAt(NOW.minusSeconds(900)).expiresAt(NOW);
            case "no-exp" -> builder.claims(values -> values.remove("exp"));
            case "no-iat" -> builder.claims(values -> values.remove("iat"));
            case "no-aud" -> builder.claims(values -> values.remove("aud"));
            case "no-sub" -> builder.claims(values -> values.remove("sub"));
            case "no-iss" -> builder.claims(values -> values.remove("iss"));
            case "long-life" -> builder.expiresAt(NOW.plusSeconds(901));
            case "not-before" -> builder.notBefore(NOW.plusSeconds(1));
        }
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256")));
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
        assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "not-base64!", "YWJjZA=="})
    void refusesMissingOrShortSigningKey(String secret) {
        assertThatThrownBy(() -> new AuthTokens(new MockEnvironment().withProperty("boxbox.auth.jwt-secret", secret), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void passwordHashIsSaltedAndPreservesUnicodeWithoutBcryptTruncation() {
        var encoder = new AuthConfiguration().authPasswordEncoder();
        String password = "😀".repeat(64);
        String first = encoder.encode(password), second = encoder.encode(password);
        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches("😀".repeat(63) + "😃", first)).isFalse();
    }
}
