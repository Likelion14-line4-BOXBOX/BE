package com.boxbox.garage.overview;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.garage.overview.dto.GarageMarketSummary;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-garage-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/garage-cleanup.sql", "/db/profile-test-data.sql", "/db/garage-test-data.sql"})
class GarageApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected GarageService garage;
    @MockitoBean protected GarageMarketSummaryReader market;

    @BeforeEach void marketReady() {
        reset(market);
        when(market.find(anyLong())).thenReturn(Optional.of(new GarageMarketSummary(BigInteger.valueOf(1037000), new BigDecimal("3.7"), 12L,
                Instant.parse("2026-11-09T07:00:00Z"))));
    }

    @Test void privateOtherReturnsPublicProfileAndStoredStampSummaryWithoutReadingMarket() throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.profile.length()").value(6))
                .andExpect(jsonPath("$.data.profile.userId").value("1"))
                .andExpect(jsonPath("$.data.profile.favoriteTeam.teamId").value("1"))
                .andExpect(jsonPath("$.data.profile.favoriteDriver.driverId").value("11"))
                .andExpect(jsonPath("$.data.profile.favoriteDriver.number").value(nullValue()))
                .andExpect(jsonPath("$.data.profile.showMarketAssets").doesNotExist())
                .andExpect(jsonPath("$.data.marketSummary").value(nullValue()))
                .andExpect(jsonPath("$.data.stampSummary.length()").value(3))
                .andExpect(jsonPath("$.data.stampSummary.season").value(2026))
                .andExpect(jsonPath("$.data.stampSummary.watchedCount").value(1))
                .andExpect(jsonPath("$.data.stampSummary.totalRaces").value(3))
                .andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.meta.requestId").isString()).andExpect(jsonPath("$.meta.serverTime").isString());
        verifyNoInteractions(market);
    }

    @Test void ownerSeesAssetsEvenWhenPublicSettingIsFalse() throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOwner").value(true))
                .andExpect(jsonPath("$.data.profile.length()").value(7))
                .andExpect(jsonPath("$.data.profile.showMarketAssets").value(false))
                .andExpect(jsonPath("$.data.marketSummary.length()").value(4))
                .andExpect(jsonPath("$.data.marketSummary.totalAssets").value(1037000))
                .andExpect(jsonPath("$.data.marketSummary.returnRate").value(3.7))
                .andExpect(jsonPath("$.data.marketSummary.rank").value(12))
                .andExpect(jsonPath("$.data.marketSummary.snapshotAt").value("2026-11-09T07:00:00Z"));
        verify(market).find(1);
    }

    @Test void publicOtherUsesTargetsMarketAndOmitsOwnerSettingAndFavorites() throws Exception {
        mvc.perform(get("/api/users/2/garage").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.profile.userId").value("2"))
                .andExpect(jsonPath("$.data.profile.showMarketAssets").doesNotExist())
                .andExpect(jsonPath("$.data.profile.favoriteTeam").value(nullValue()))
                .andExpect(jsonPath("$.data.profile.favoriteDriver").value(nullValue()))
                .andExpect(jsonPath("$.data.profile.avatarAssetId").value("avatar_default"))
                .andExpect(jsonPath("$.data.stampSummary.watchedCount").value(0));
        verify(market).find(2);
        verify(market, never()).find(1);
    }

    @Test void noTradesDoesNotMeanUnavailableAndMissingRankOrDenominatorRemainsNull() throws Exception {
        long actualCash = jdbc.queryForObject("SELECT coin_balance FROM users WHERE id=1", Long.class);
        when(market.find(1)).thenReturn(Optional.of(new GarageMarketSummary(BigInteger.valueOf(actualCash), null, null, Instant.parse("2026-11-09T07:00:00Z"))));
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.marketSummary.totalAssets").value(actualCash))
                .andExpect(jsonPath("$.data.marketSummary.returnRate").value(nullValue()))
                .andExpect(jsonPath("$.data.marketSummary.rank").value(nullValue()))
                .andExpect(jsonPath("$.data.marketSummary.length()").value(4));
    }

    @Test void zeroAssetsAndNegativeReturnAreValidProviderValuesAndAreNotRecalculated() {
        var value = new GarageMarketSummary(BigInteger.ZERO, new BigDecimal("-37.12345678"), null, Instant.parse("2026-11-09T07:00:00Z"));
        when(market.find(1)).thenReturn(Optional.of(value));
        assertThat(garage.get("viewer", "1", Map.of()).marketSummary()).isEqualTo(value);
    }

    @Test void missingTargetMarketDataReturns503ForOwnerAndPublicOtherButPrivateOtherStillWorks() throws Exception {
        when(market.find(anyLong())).thenReturn(Optional.empty());
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/users/2/garage").principal(() -> "viewer"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MARKET_SUMMARY_UNAVAILABLE"));
        clearInvocations(market);
        mvc.perform(get("/api/users/1/garage").principal(() -> "other")).andExpect(status().isOk());
        verifyNoInteractions(market);
    }

    @Test void unavailableOrFailingMarketIsNeverQueriedForPrivateOther() throws Exception {
        when(market.find(anyLong())).thenThrow(new IllegalStateException("source unavailable"));
        mvc.perform(get("/api/users/1/garage").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.marketSummary").value(nullValue()));
        verifyNoInteractions(market);
    }

    @Test void suspendedReadAccountAndTargetFollowExistingStampReadPolicy() throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> "suspended")).andExpect(status().isOk());
        mvc.perform(get("/api/users/3/garage").principal(() -> "viewer")).andExpect(status().isOk());
        verifyNoInteractions(market);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "unknown", "withdrawn", "deleted"})
    void invalidPrincipalCannotRead(String login) throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> login))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(market);
    }

    @Test void anonymousSeesFavoritesAndStampsButCannotImpersonateOwnerWithHeaders() throws Exception {
        for (var request : java.util.List.of(get("/api/users/1/garage"),
                get("/api/users/1/garage").header("X-User-Id", "1"))) {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isOwner").value(false))
                    .andExpect(jsonPath("$.data.profile.favoriteTeam.teamId").value("1"))
                    .andExpect(jsonPath("$.data.profile.favoriteDriver.driverId").value("11"))
                    .andExpect(jsonPath("$.data.profile.showMarketAssets").doesNotExist())
                    .andExpect(jsonPath("$.data.profile.loginId").doesNotExist())
                    .andExpect(jsonPath("$.data.profile.passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.data.marketSummary").value(nullValue()))
                    .andExpect(jsonPath("$.data.stampSummary.watchedCount").value(1));
        }
        verifyNoInteractions(market);
    }

    @Test void anonymousCanReadPublicAssetsButInvalidBearerIsStillRejected() throws Exception {
        mvc.perform(get("/api/users/2/garage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.marketSummary.totalAssets").value(1037000))
                .andExpect(jsonPath("$.data.profile.showMarketAssets").doesNotExist());
        verify(market).find(2);
        clearInvocations(market);
        mvc.perform(get("/api/users/1/garage").header("Authorization", "Bearer viewer")).andExpect(status().isUnauthorized());
        verifyNoInteractions(market);
    }

    @Test void anonymousValidatesTargetAndQueryAndCannotUseOwnProfileEndpoints() throws Exception {
        mvc.perform(get("/api/users/0/garage")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/999/garage")).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/1/garage?isOwner=true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/me").contentType("application/json").content("{\"showMarketAssets\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(market);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "u001", "1.0", "9223372036854775808"})
    void invalidIdIs400BeforeMarketLookup(String id) throws Exception {
        mvc.perform(get("/api/users/" + id + "/garage").principal(() -> "viewer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(market);
    }

    @ParameterizedTest @ValueSource(strings = {"4", "5", "999"})
    void absentWithdrawnOrDeletedTargetIs404BeforeMarketLookup(String id) throws Exception {
        mvc.perform(get("/api/users/" + id + "/garage").principal(() -> "viewer"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
        verifyNoInteractions(market);
    }

    @ParameterizedTest @ValueSource(strings = {"season", "size", "userId", "isOwner", "showMarketAssets"})
    void queryCannotSelectSeasonOrOverrideIdentityAndPrivacy(String key) throws Exception {
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer").param(key, "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(market);
    }

    @Test void noRacesMeansZeroSummaryAndReadNeverCreatesStamp() {
        var before = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        var profile = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        var result = garage.get("other", "1", Map.of());
        assertThat(garage.get(null, "1", Map.of())).isEqualTo(result);
        assertThat(result.stampSummary().watchedCount()).isEqualTo(1);
        assertThat(result.stampSummary().totalRaces()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(profile);
        jdbc.update("DELETE FROM chat_participants"); jdbc.update("DELETE FROM chat_rooms"); jdbc.update("DELETE FROM races");
        var empty = garage.get("other", "1", Map.of()).stampSummary();
        assertThat(empty.season()).isEqualTo(2026);
        assertThat(empty.watchedCount()).isZero(); assertThat(empty.totalRaces()).isZero();
    }

    @Test void unsupportedMethodIs405() throws Exception {
        mvc.perform(post("/api/users/1/garage").principal(() -> "viewer")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(market);
    }

    @Test void publicGarageReusesKoreanFavoritesWithoutExposingPrivateSettings() throws Exception {
        jdbc.update("UPDATE teams SET name='Ferrari',name_ko='페라리' WHERE id=1");
        jdbc.update("UPDATE drivers SET name='Charles Leclerc',name_ko='샤를 르클레르' WHERE id=11");
        mvc.perform(get("/api/users/1/garage").principal(() -> "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.favoriteTeam.name").value("페라리"))
                .andExpect(jsonPath("$.data.profile.favoriteTeam.nameEn").value("Ferrari"))
                .andExpect(jsonPath("$.data.profile.favoriteDriver.name").value("샤를 르클레르"))
                .andExpect(jsonPath("$.data.profile.favoriteDriver.nameEn").value("Charles Leclerc"))
                .andExpect(jsonPath("$.data.profile.favoriteDriver.teamName").value("페라리"))
                .andExpect(jsonPath("$.data.profile.showMarketAssets").doesNotExist());
    }

}
