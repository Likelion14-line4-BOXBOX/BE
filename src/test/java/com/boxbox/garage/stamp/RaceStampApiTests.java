package com.boxbox.garage.stamp;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-stamp-read-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql",
        "boxbox.chat.active-season=2025"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/chat-test-data.sql", "/db/chat-write-test-data.sql", "/db/race-stamp-test-data.sql"})
class RaceStampApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected RaceStampService stamps;

    @Test void returnsWholeSeasonInRoundOrderWithStoredStampsAndExplicitNulls() throws Exception {
        mvc.perform(get("/api/users/1/race-stamps?season=2026").principal(() -> "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.season").value(2026)).andExpect(jsonPath("$.data.watchedCount").value(1))
                .andExpect(jsonPath("$.data.totalRaces").value(7)).andExpect(jsonPath("$.data.items.length()").value(7))
                .andExpect(jsonPath("$.data.items[*].raceId").value(is(List.of("1", "2", "3", "4", "6", "7", "8"))))
                .andExpect(jsonPath("$.data.items[0].length()").value(7))
                .andExpect(jsonPath("$.data.items[0].recordType").value("WATCHED"))
                .andExpect(jsonPath("$.data.items[0].source").value("CHAT"))
                .andExpect(jsonPath("$.data.items[0].recordedAt").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[2].recordType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].source").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].recordedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].length()").value(7))
                .andExpect(jsonPath("$.data.items[6].raceName").value("채팅방 없는 GP"))
                .andExpect(jsonPath("$.data.items[6].recordedAt").value(nullValue()))
                .andExpect(jsonPath("$.meta.requestId").isString()).andExpect(jsonPath("$.meta.serverTime").isString());
    }

    @Test void omittedSeasonUsesConfiguredActiveSeasonAndPreservesHistoricalAutomaticRecord() {
        var result = stamps.list("viewer", "1", Map.of());
        assertThat(result.season()).isEqualTo(2025);
        assertThat(result.totalRaces()).isEqualTo(1);
        assertThat(result.watchedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().raceId()).isEqualTo("5");
        assertThat(result.items().getFirst().recordedAt()).isEqualTo(java.time.Instant.parse("2025-11-01T00:00:00Z"));
    }

    @Test void otherUsersAndEmptySeasonNeverReuseViewersStamps() throws Exception {
        mvc.perform(get("/api/users/2/race-stamps?season=2026").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.watchedCount").value(0))
                .andExpect(jsonPath("$.data.items[0].source").value(nullValue()))
                .andExpect(jsonPath("$.data.items[6].source").value(nullValue()));
        mvc.perform(get("/api/users/1/race-stamps?season=2099").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRaces").value(0))
                .andExpect(jsonPath("$.data.watchedCount").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test void suspendedAccountCanStillReadAndBeViewedLikeChatReadPolicy() throws Exception {
        mvc.perform(get("/api/users/3/race-stamps?season=2026").principal(() -> "suspended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRaces").value(7))
                .andExpect(jsonPath("$.data.watchedCount").value(0));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "missing", "withdrawn", "deleted"})
    void invalidPrincipalsCannotRead(String principal) throws Exception {
        mvc.perform(get("/api/users/1/race-stamps").principal(() -> principal))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test void anonymousAndForgedHeadersAreNotAuthentication() throws Exception {
        mvc.perform(get("/api/users/1/race-stamps")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/1/race-stamps").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/1/race-stamps").header("Authorization", "Bearer viewer"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "u001", "1.0", "9223372036854775808"})
    void invalidUserIdIsBadRequest(String id) throws Exception {
        mvc.perform(get("/api/users/" + id + "/race-stamps").principal(() -> "viewer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "abc", "26", "0202", "0000", "10000", "2026.0", "+2026", "-2026", "2026 ", "２０２６"})
    void invalidSeasonIsBadRequest(String season) throws Exception {
        mvc.perform(get("/api/users/1/race-stamps").param("season", season).principal(() -> "viewer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest @ValueSource(strings = {"4", "5", "999"})
    void absentOrWithdrawnTargetIsNotFound(String id) throws Exception {
        mvc.perform(get("/api/users/" + id + "/race-stamps").principal(() -> "viewer"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test void unsupportedQueryAndMethodAreRejected() throws Exception {
        mvc.perform(get("/api/users/1/race-stamps?size=10").principal(() -> "viewer"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/1/race-stamps").principal(() -> "viewer"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test void readNeverAwardsOrChangesParticipationMessagesOrEvents() {
        var before = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        stamps.list("viewer", "1", Map.of("season", "2026"));
        stamps.list("other", "1", Map.of("season", "2025"));
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_events", Long.class)).isZero();
    }

    @Test void stampsIncludeKoreanNamesForRacesWithOrWithoutRoomsAndDoNotChangeParticipation() throws Exception {
        var before = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        jdbc.update("UPDATE races SET name='Las Vegas Grand Prix',name_ko='라스베이거스 그랑프리' WHERE id=1");
        jdbc.update("UPDATE races SET name='Abu Dhabi Grand Prix',name_ko='아부다비 그랑프리' WHERE id=8");
        String second = jdbc.queryForObject("SELECT name FROM races WHERE id=2",String.class);
        mvc.perform(get("/api/users/1/race-stamps?season=2026").principal(() -> "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raceName").value("라스베이거스 그랑프리"))
                .andExpect(jsonPath("$.data.items[0].raceNameEn").value("Las Vegas Grand Prix"))
                .andExpect(jsonPath("$.data.items[1].raceName").value(second))
                .andExpect(jsonPath("$.data.items[1].raceNameEn").value(second))
                .andExpect(jsonPath("$.data.items[6].raceName").value("아부다비 그랑프리"))
                .andExpect(jsonPath("$.data.items[6].raceNameEn").value("Abu Dhabi Grand Prix"));
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(before);
    }

}
