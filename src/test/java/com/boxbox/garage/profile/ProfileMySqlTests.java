package com.boxbox.garage.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boxbox.common.api.ApiException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 전체 ERD를 미리 적용한 로컬 전용 DB에서만 명시적으로 실행한다. 공통 DB 사용 금지. */
@EnabledIfEnvironmentVariable(named = "RUN_PROFILE_MYSQL_TESTS", matches = "true")
class ProfileMySqlTests extends ProfileApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProfileService profileService;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_profile_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PROFILE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PROFILE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test
    void mysqlStoresTwentySupplementaryUnicodeCharacters() throws Exception {
        String nickname = "😀".repeat(20);
        mvc.perform(patch("/api/me").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=1", String.class)).isEqualTo(nickname);
    }

    @Test
    void mysqlNicknameComparisonFollowsAccentInsensitiveCollation() throws Exception {
        jdbc.update("UPDATE users SET nickname='Café' WHERE id=2");
        mvc.perform(patch("/api/me").principal(() -> "viewer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"cafe\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void twoMembersCannotClaimTheSameNicknameConcurrently() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> renameAfterStart("viewer", start));
            var second = pool.submit(() -> renameAfterStart("other", start));
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "NICKNAME_TAKEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname='동시선점'", Long.class))
                .isEqualTo(1L);
    }

    private String renameAfterStart(String loginId, CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try {
            profileService.updateProfile(loginId, Map.of("nickname", "동시선점"));
            return "OK";
        } catch (ApiException exception) {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            return exception.getCode();
        }
    }
}
