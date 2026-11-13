package com.boxbox.garage.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "boxbox.profile.allowed-avatar-ids=avatar_default,avatar_test,team_test,driver_test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/db/profile-test-data.sql")
class ProfileApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProfileService profileService;

    @Test
    void returnsOnlyOwnProfileAndStoredReferenceInformation() throws Exception {
        mvc.perform(get("/api/me").principal(() -> "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.nickname").value("기존닉네임"))
                .andExpect(jsonPath("$.data.bio").value("기존소개"))
                .andExpect(jsonPath("$.data.avatarAssetId").value("avatar_default"))
                .andExpect(jsonPath("$.data.favoriteTeam.teamId").value("1"))
                .andExpect(jsonPath("$.data.favoriteTeam.championshipRank").value(3))
                .andExpect(jsonPath("$.data.favoriteDriver.driverId").value("11"))
                .andExpect(jsonPath("$.data.favoriteDriver.number").value(1))
                .andExpect(jsonPath("$.data.favoriteDriver.teamName").value("팀 A"))
                .andExpect(jsonPath("$.data.favoriteDriver.championshipRank").value(7))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-05T00:00:00Z"))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.loginId").doesNotExist())
                .andExpect(jsonPath("$.data.coinBalance").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void unsetFavoritesAreExplicitNullAndBioIsEmpty() throws Exception {
        mvc.perform(get("/api/me").principal(() -> "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteTeam").value(nullValue()))
                .andExpect(jsonPath("$.data.favoriteDriver").value(nullValue()))
                .andExpect(jsonPath("$.data.bio").value(""))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(false));
    }

    @Test
    void anonymousAndClientSuppliedIdentityCannotReadOrWriteProfile() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer viewer").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/me").header("Authorization", "Bearer viewer")
                        .header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"위장회원\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        assertThat(value("nickname")).isEqualTo("기존닉네임");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "suspended", "withdrawn", "deleted", "", " "})
    void unavailablePrincipalCannotReadOrWrite(String loginId) throws Exception {
        mvc.perform(get("/api/me").principal(() -> loginId)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/me").principal(() -> loginId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void partialUpdateTrimsNicknameAndPreservesAllOtherState() throws Exception {
        update("{\"nickname\":\"  변경닉네임  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("변경닉네임"))
                .andExpect(jsonPath("$.data.bio").value("기존소개"))
                .andExpect(jsonPath("$.data.favoriteDriver.driverId").value("11"))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.onboardingCompleted").doesNotExist());
        assertThat(value("nickname")).isEqualTo("변경닉네임");
        assertThat(value("password_hash")).isEqualTo("test-only-hash");
        assertThat(numberValue("coin_balance")).isEqualTo(12345L);
        assertThat(numberValue("wallet_version")).isEqualTo(7L);
        assertThat(value("onboarding_completed")).isEqualTo(true);
        assertThat(value("login_id")).isEqualTo("viewer");
        assertThat(value("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=2", String.class)).isEqualTo("TakenName");
    }

    @Test
    void emptyPatchIsNoOpAndSameNicknameIsAllowed() throws Exception {
        Object before = value("updated_at");
        update("{}").andExpect(status().isOk());
        assertThat(value("updated_at")).isEqualTo(before);
        update("{\"nickname\":\"기존닉네임\"}").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TakenName", "takenname", "  TakenName  "})
    void duplicateNicknameUsesDatabaseComparisonAndRollsBackOtherFields(String nickname) throws Exception {
        update("{\"nickname\":\"" + nickname + "\",\"bio\":\"실패한소개\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"));
        assertThat(value("bio")).isEqualTo("기존소개");
        assertThat(value("nickname")).isEqualTo("기존닉네임");
    }

    @Test
    void explicitNullClearsFavoritesAndResetsAvatarWhileEmptyBioClearsText() throws Exception {
        update("{\"avatarAssetId\":\"avatar_test\",\"showMarketAssets\":true}").andExpect(status().isOk());
        update("{\"avatarAssetId\":null,\"favoriteTeamId\":null,\"favoriteDriverId\":null,\"bio\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarAssetId").value("avatar_default"))
                .andExpect(jsonPath("$.data.favoriteTeam").value(nullValue()))
                .andExpect(jsonPath("$.data.favoriteDriver").value(nullValue()))
                .andExpect(jsonPath("$.data.bio").value(""))
                .andExpect(jsonPath("$.data.showMarketAssets").value(true));
        assertThat(value("favorite_team_id")).isNull();
        assertThat(value("favorite_driver_id")).isNull();
        update("{\"showMarketAssets\":false}").andExpect(status().isOk());
        assertThat(value("show_market_assets")).isEqualTo(false);
    }

    @Test
    void teamAndDriverChangeTogetherAndUnknownRanksStayNull() throws Exception {
        update("{\"favoriteTeamId\":\"2\",\"favoriteDriverId\":\"12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteTeam.teamId").value("2"))
                .andExpect(jsonPath("$.data.favoriteTeam.championshipRank").value(nullValue()))
                .andExpect(jsonPath("$.data.favoriteDriver.driverId").value("12"))
                .andExpect(jsonPath("$.data.favoriteDriver.championshipRank").value(nullValue()));
        assertThat(numberValue("favorite_team_id")).isEqualTo(2L);
        assertThat(numberValue("favorite_driver_id")).isEqualTo(12L);
    }

    @Test
    void teamCanChangeWhenDriverIsExplicitlyCleared() throws Exception {
        update("{\"favoriteTeamId\":\"2\",\"favoriteDriverId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoriteDriver").value(nullValue()));
        update("{\"favoriteTeamId\":null}").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"favoriteTeamId\":\"2\"}", "{\"favoriteTeamId\":null}",
            "{\"favoriteTeamId\":\"2\",\"favoriteDriverId\":\"11\"}",
            "{\"favoriteDriverId\":\"12\"}", "{\"favoriteDriverId\":\"13\"}",
            "{\"favoriteTeamId\":null,\"favoriteDriverId\":\"11\"}"
    })
    void invalidFavoriteCombinationDoesNotPartiallySave(String body) throws Exception {
        update(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(numberValue("favorite_team_id")).isEqualTo(1L);
        assertThat(numberValue("favorite_driver_id")).isEqualTo(11L);
    }

    @Test
    void missingTeamAndDriverReturnDistinctNotFoundErrors() throws Exception {
        update("{\"favoriteTeamId\":\"999\",\"favoriteDriverId\":null}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_NOT_FOUND"));
        update("{\"favoriteDriverId\":\"999\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DRIVER_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar_test", "team_test", "driver_test", "avatar_default"})
    void configuredPresetsCanBeSelected(String id) throws Exception {
        update("{\"avatarAssetId\":\"" + id + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarAssetId").value(id));
        assertThat(value("avatar_asset_id")).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"nickname\":null}", "{\"nickname\":\"a\"}", "{\"nickname\":\"   \"}",
            "{\"nickname\":\"123456789012345678901\"}", "{\"nickname\":123}",
            "{\"bio\":null}", "{\"bio\":false}", "{\"showMarketAssets\":null}",
            "{\"showMarketAssets\":\"true\"}", "{\"showMarketAssets\":1}",
            "{\"avatarAssetId\":\"unknown\"}", "{\"avatarAssetId\":\"\"}", "{\"avatarAssetId\":1}",
            "{\"favoriteTeamId\":1}", "{\"favoriteTeamId\":\"0\"}", "{\"favoriteTeamId\":\"-1\"}",
            "{\"favoriteTeamId\":\"01\"}", "{\"favoriteTeamId\":\"+1\"}",
            "{\"favoriteTeamId\":\"9223372036854775808\"}", "{\"favoriteDriverId\":\"x\"}",
            "{\"favoriteDriverId\":false}", "{\"nickname\":[]}",
            "{\"userId\":\"2\"}", "{\"coinBalance\":100}", "{\"onboardingCompleted\":true}"
    })
    void invalidValuesAndReadOnlyFieldsUseCommonErrorEnvelope(String body) throws Exception {
        update(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
        assertThat(value("nickname")).isEqualTo("기존닉네임");
    }

    @Test
    void acceptsLengthBoundariesAndRejectsOverlongBioAtomically() throws Exception {
        update("{\"nickname\":\"가나\",\"bio\":\"" + "가".repeat(100) + "\"}")
                .andExpect(status().isOk());
        update("{\"nickname\":\"" + "가".repeat(20) + "\"}").andExpect(status().isOk());
        update("{\"nickname\":\"되돌리기\",\"bio\":\"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(value("nickname")).isEqualTo("가".repeat(20));
        assertThat(value("bio")).isEqualTo("가".repeat(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{broken", "\"text\""})
    void malformedOrNonObjectBodyUsesCommonErrorEnvelope(String body) throws Exception {
        update(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void concurrentPartialUpdatesPreserveBothChanges() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var nickname = pool.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return profileService.updateProfile("viewer", Map.of("nickname", "동시수정"));
            });
            var bio = pool.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return profileService.updateProfile("viewer", Map.of("bio", "동시소개"));
            });
            start.countDown();
            nickname.get(10, TimeUnit.SECONDS);
            bio.get(10, TimeUnit.SECONDS);
        }
        assertThat(value("nickname")).isEqualTo("동시수정");
        assertThat(value("bio")).isEqualTo("동시소개");
    }

    private ResultActions update(String body) throws Exception {
        return mvc.perform(patch("/api/me").principal(() -> "viewer")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Object value(String column) {
        // column은 테스트 코드가 정한 리터럴만 전달한다.
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE id=1", Object.class);
    }

    private Long numberValue(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE id=1", Long.class);
    }
}
