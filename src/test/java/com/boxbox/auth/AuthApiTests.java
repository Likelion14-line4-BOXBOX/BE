package com.boxbox.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthApiTests.TimeConfiguration.class)
@Sql("/db/auth-cleanup.sql")
class AuthApiTests {
    static final String ORIGIN = "http://localhost:5173";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService service;
    @Autowired AuthTokens tokens;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock testAuthClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T00:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @BeforeEach void nextWindow() { clock.advance(Duration.ofMinutes(2)); }

    @Test void demoLoginSharesAccountWithIndependentSessionsAndPreservesWallet() throws Exception {
        signup("boxbox5", "1234abcd", "공용체험", 201);
        jdbc.update("UPDATE users SET coin_balance=997600 WHERE login_id='boxbox5'");
        var first = mvc.perform(post("/api/auth/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn();
        var second = mvc.perform(post("/api/auth/demo")).andExpect(status().isOk()).andReturn();
        assertThat(tokens.verify(access(first))).isEqualTo(tokens.verify(access(second)));
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("coin_ledger")).isEqualTo(1);
        assertThat(count("auth_sessions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT coin_balance FROM users", Long.class)).isEqualTo(997600L);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("공용체험"));
        mvc.perform(post("/api/auth/logout").cookie(refreshCookie(second)).header("Origin", ORIGIN))
                .andExpect(status().isNoContent());
        refresh(refreshCookie(first), 200);
        refresh(refreshCookie(second), 401);
    }

    @Test void demoLoginRequiresExistingActiveConfiguredAccount() throws Exception {
        mvc.perform(post("/api/auth/demo")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEMO_ACCOUNT_UNAVAILABLE"));
        signup("ordinary", "1234abcd", "일반회원", 201);
        mvc.perform(post("/api/auth/demo")).andExpect(status().isServiceUnavailable());
        signup("boxbox5", "1234abcd", "공용체험", 201);
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE login_id='boxbox5'");
        mvc.perform(post("/api/auth/demo")).andExpect(status().isServiceUnavailable());
        jdbc.update("UPDATE users SET status='ACTIVE',deleted_at=CURRENT_TIMESTAMP WHERE login_id='boxbox5'");
        mvc.perform(post("/api/auth/demo")).andExpect(status().isServiceUnavailable());
        assertThat(count("auth_sessions")).isZero();
    }

    @Test void demoLoginRejectsBodyAndQueryParameters() throws Exception {
        mvc.perform(post("/api/auth/demo?loginId=ordinary")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"ordinary\"}")).andExpect(status().isBadRequest());
        assertThat(count("auth_sessions")).isZero();
    }

    @Test void signupCreatesOneUserBalanceAndLedgerWithoutLoggingIn() throws Exception {
        var result = signup("  BoxBox_1  ", "1234abcd", "  새회원  ", 201);
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("loginId").asString()).isEqualTo("boxbox_1");
        assertThat(data.get("nickname").asString()).isEqualTo("새회원");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "1234abcd", "accessToken");
        assertThat(count("auth_sessions")).isZero();
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("coin_ledger")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coin_balance FROM users", Long.class)).isEqualTo(1_000_000L);
        assertThat(jdbc.queryForObject("SELECT balance_after FROM coin_ledger WHERE entry_type='INITIAL_GRANT'", Long.class)).isEqualTo(1_000_000L);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).startsWith("{pbkdf2-sha256-600k}").doesNotContain("1234abcd");
    }

    @ParameterizedTest @ValueSource(ints = {8, 64})
    void acceptsPasswordBoundariesWithoutCompositionRequirements(int size) throws Exception {
        signup("boundary", "가".repeat(size), "경계회원", 201);
        login("boundary", "가".repeat(size), 200);
    }
    @Test void supplementaryUnicodeCountsAsOneCharacter() throws Exception {
        signup("unicode", "😀".repeat(64), "유니코드", 201);
        login("unicode", "😀".repeat(64), 200);
        signup("unicode2", "😀".repeat(65), "거절회원", 400);
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 4, 7, 65})
    void rejectsInvalidSignupPasswordLengths(int size) throws Exception {
        signup("boundary", "a".repeat(size), "경계회원", 400);
        assertThat(count("users")).isZero();
        assertThat(count("coin_ledger")).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"abc", "a@b.com", "abcd-", "한글아이디", "a.bcd", "a bcd", "abcdefghijklmnopqrstu"})
    void signupRequiresOrdinaryAsciiLoginId(String loginId) throws Exception {
        signup(loginId, "1234abcd", "가입회원", 400);
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "null", "[]", "{\"loginId\":123,\"password\":\"1234abcd\",\"nickname\":\"회원\"}",
            "{\"loginId\":\"boxbox\",\"password\":null,\"nickname\":\"회원\"}",
            "{\"loginId\":\"boxbox\",\"password\":\"1234abcd\",\"nickname\":\"회원\",\"email\":\"x@y.z\"}"
    })
    void rejectsMissingNullWrongTypesAndUnknownFields(String body) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
    @Test void preservesPasswordSpacesAndCase() throws Exception {
        signup("caseuser", " Abcd가1 ", "암호회원", 201);
        login(" CASEUSER ", " Abcd가1 ", 200);
        login("caseuser", "Abcd가1", 401);
        login("caseuser", " abcd가1 ", 401);
    }
    @Test void duplicateSignupDoesNotGrantCoinsAgain() throws Exception {
        signup("DupeUser", "1234abcd", "중복회원", 201);
        signup(" DUPEUSER ", "1234abcd", "다른회원", 409);
        signup("another", "1234abcd", "중복회원", 409);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("coin_ledger")).isEqualTo(1);
    }
    @Test void loginIssuesSecureCookieAndAuthenticatesExistingProfileApi() throws Exception {
        signup("profileuser", "1234abcd", "프로필회원", 201);
        var result = login("profileuser", "1234abcd", 200);
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "Path=/api/auth", "SameSite=Lax", "Max-Age=").doesNotContain("Domain=");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String access = access(result);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("프로필회원"));
        assertThat(count("auth_sessions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", byte[].class)).isEqualTo(AuthTokens.hash(refreshCookie(result).getValue()));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken", "passwordHash");
    }
    @Test void nonexistentIdWrongPasswordAndInactiveAccountHaveSameLoginError() throws Exception {
        signup("member", "1234abcd", "로그인회원", 201);
        for (String name : List.of("member", "missing")) {
            var result = login(name, "incorrect", 401);
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("error").get("code").asString()).isEqualTo("LOGIN_FAILED");
        }
        for (String state : List.of("SUSPENDED", "WITHDRAWN")) {
            jdbc.update("UPDATE users SET status=?", state);
            login("member", "1234abcd", 401);
        }
        jdbc.update("UPDATE users SET status='ACTIVE',deleted_at=CURRENT_TIMESTAMP");
        login("member", "1234abcd", 401);
        assertThat(count("auth_sessions")).isZero();
    }
    @Test void loginDoesNotApplySignupMinimumLength() throws Exception {
        login("missing", "a", 401);
        login("missing", "", 400);
        login("missing", "a".repeat(65), 400);
    }
    @Test void loginAccountLimitAppliesBeforeExpensivePasswordVerification() throws Exception {
        for (int i = 0; i < 10; i++) login("missing", "incorrect", 401);
        var rejected = login("missing", "incorrect", 429);
        assertThat(rejected.getResponse().getContentAsString()).contains("RATE_LIMITED");
        clock.advance(Duration.ofSeconds(60));
        login("missing", "incorrect", 401);
    }
    @Test void signupIpLimitAlsoCountsInvalidRequests() throws Exception {
        for (int i = 0; i < 10; i++) signup("limituser", "short", "제한회원", 400);
        signup("limituser", "1234abcd", "제한회원", 429);
        assertThat(count("users")).isZero();
    }
    @Test void refreshRotatesPreservesExpiryAndRejectsReplayWithCommittedRevocation() throws Exception {
        signup("rotation", "1234abcd", "회전회원", 201);
        var first = login("rotation", "1234abcd", 200);
        var expiry = jdbc.queryForObject("SELECT expires_at FROM auth_sessions", java.time.LocalDateTime.class);
        var second = refresh(refreshCookie(first), 200);
        assertThat(refreshCookie(second).getValue()).isNotEqualTo(refreshCookie(first).getValue());
        assertThat(count("auth_sessions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE rotated_at IS NOT NULL AND replaced_by_id IS NOT NULL", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT expires_at FROM auth_sessions", java.time.LocalDateTime.class)).containsOnly(expiry);
        var replay = refresh(refreshCookie(first), 401);
        assertThat(replay.getResponse().getContentAsString()).contains("REFRESH_TOKEN_REUSED");
        assertThat(replay.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NOT NULL", Long.class)).isEqualTo(2);
        refresh(refreshCookie(second), 401);
    }
    @Test void replayAndLogoutDoNotRevokeOtherLoginFamilies() throws Exception {
        signup("families", "1234abcd", "세션회원", 201);
        var first = login("families", "1234abcd", 200);
        var other = login("families", "1234abcd", 200);
        var next = refresh(refreshCookie(first), 200);
        refresh(refreshCookie(first), 401);
        var stillValid = refresh(refreshCookie(other), 200);
        mvc.perform(post("/api/auth/logout").cookie(refreshCookie(next)).header("Origin", ORIGIN)).andExpect(status().isNoContent());
        refresh(refreshCookie(stillValid), 200);
    }
    @Test void expiredRefreshIsRejectedAndRevocationPersists() throws Exception {
        signup("expiry", "1234abcd", "만료회원", 201);
        var issued = login("expiry", "1234abcd", 200);
        clock.advance(Duration.ofDays(14));
        var result = refresh(refreshCookie(issued), 401);
        assertThat(result.getResponse().getContentAsString()).contains("REFRESH_TOKEN_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions", Boolean.class)).isTrue();
    }
    @Test void changedAccountStateBlocksRefreshAndDeletedUserBlocksAccess() throws Exception {
        signup("changed", "1234abcd", "상태회원", 201);
        var issued = login("changed", "1234abcd", 200);
        jdbc.update("UPDATE users SET status='SUSPENDED'");
        refresh(refreshCookie(issued), 401);
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access(issued))).andExpect(status().isUnauthorized());
    }
    @Test void unknownAndMalformedRefreshAlwaysClearCookie() throws Exception {
        for (String raw : List.of("wrong", "a".repeat(43))) refresh(new Cookie("refreshToken", raw), 401);
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
    @Test void logoutIsIdempotentAndAccessExpiresAtFifteenMinutes() throws Exception {
        signup("logoutuser", "1234abcd", "로그아웃회원", 201);
        var issued = login("logoutuser", "1234abcd", 200);
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/auth/logout").cookie(refreshCookie(issued)).header("Origin", ORIGIN))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        refresh(refreshCookie(issued), 401);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access(issued))).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(900));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access(issued))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }
    @Test void invalidAuthorizationCannotImpersonateUserEvenOnPublicEndpoints() throws Exception {
        for (String header : List.of("Bearer viewer", "Basic abc", "Bearer ", "Bearer " + "a".repeat(5000)))
            mvc.perform(get("/api/me").header("Authorization", header).header("X-User-Id", "1"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/me").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
    }
    @Test void csrfAndCorsRequireExactAllowedOrigin() throws Exception {
        for (String origin : List.of("null", "http://localhost:5173.evil.test", "https://evil.test"))
            mvc.perform(post("/api/auth/login").header("Origin", origin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));
        for (String path : List.of("refresh", "logout"))
            mvc.perform(post("/api/auth/" + path).cookie(new Cookie("refreshToken", "a".repeat(43))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_VALIDATION_FAILED"));
        mvc.perform(options("/api/me").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isNoContent()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
    @Test void authEndpointsRejectQueryBodyAndDuplicateCookies() throws Exception {
        mvc.perform(post("/api/auth/refresh?token=x")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN)
                .cookie(new Cookie("refreshToken", "a".repeat(43)), new Cookie("refreshToken", "b".repeat(43))))
                .andExpect(status().isUnauthorized());
    }
    @Test void matrixPathParametersCannotSkipCookieOriginValidation() throws Exception {
        mvc.perform(post("/api/auth/refresh;client=web").cookie(new Cookie("refreshToken", "a".repeat(43))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_VALIDATION_FAILED"));
    }
    @Test void concurrentRefreshProducesOneReplacementThenRevokesReusedFamily() throws Exception {
        signup("concurrent", "1234abcd", "동시회원", 201);
        String raw = refreshCookie(login("concurrent", "1234abcd", 200)).getValue();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attemptRefresh(raw, start));
            var two = pool.submit(() -> attemptRefresh(raw, start));
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "REFRESH_TOKEN_REUSED");
        }
        assertThat(count("auth_sessions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NULL", Long.class)).isZero();
    }
    @Test void concurrentSignupCannotDuplicateMemberOrGrant() throws Exception {
        var input = AuthInput.signup(Map.of("loginId", "parallel", "password", "1234abcd", "nickname", "병렬회원"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attemptSignup(input, start));
            var two = pool.submit(() -> attemptSignup(input, start));
            start.countDown();
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "LOGIN_ID_ALREADY_EXISTS");
        }
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("coin_ledger")).isEqualTo(1);
    }
    @Test void ledgerFailureRollsBackNewMember() {
        jdbc.execute("ALTER TABLE coin_ledger ADD CONSTRAINT auth_test_failure CHECK(amount_delta < 0)");
        try {
            assertThatThrownBy(() -> service.signup(AuthInput.signup(Map.of("loginId", "rollback", "password", "1234abcd", "nickname", "롤백회원"))))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("users")).isZero();
            assertThat(count("coin_ledger")).isZero();
        } finally { dropLedgerFailure(); }
    }
    void dropLedgerFailure() { jdbc.execute("ALTER TABLE coin_ledger DROP CONSTRAINT auth_test_failure"); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    MvcResult signup(String id, String password, String nickname, int status) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", id, "password", password, "nickname", nickname))))
                .andExpect(status().is(status)).andReturn();
    }
    MvcResult login(String id, String password, int status) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", id, "password", password))))
                .andExpect(status().is(status)).andReturn();
    }
    MvcResult refresh(Cookie cookie, int status) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin", ORIGIN))
                .andExpect(status().is(status)).andReturn();
    }
    String access(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asString(); }
    Cookie refreshCookie(MvcResult result) {
        String value = result.getResponse().getHeader("Set-Cookie").split(";", 2)[0].substring("refreshToken=".length());
        return new Cookie("refreshToken", value);
    }
    private String attemptRefresh(String raw, CountDownLatch start) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try { service.refresh(raw); return "OK"; } catch (AuthService.RefreshFailure failure) { return failure.getCode(); }
    }
    private String attemptSignup(AuthInput.Signup input, CountDownLatch start) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try { service.signup(input); return "OK"; } catch (com.boxbox.common.api.ApiException failure) { return failure.getCode(); }
    }
}
