package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-chat-read-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/chat-schema.sql",
        "boxbox.chat.active-season=2026"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/db/chat-test-data.sql")
class ChatReadApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ChatService service;
    @Autowired protected ChatPresenceRegistry presence;

    @BeforeEach
    void operatingWindows() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        window(1, now.minusSeconds(86400), now.plusSeconds(86400));
        window(2, now.plusSeconds(86400), now.plusSeconds(3 * 86400));
        window(3, now.minusSeconds(4 * 86400), now.minusSeconds(86400));
        window(4, now.minusSeconds(86400), now.plusSeconds(7 * 86400));
        window(5, now.minusSeconds(86400), now.plusSeconds(2 * 86400));
        window(6, now.minusSeconds(86400), now.plusSeconds(2 * 86400));
        window(7, now.minusSeconds(4 * 86400), now.minusSeconds(86400));
    }

    @Test
    void publicRoomListHasContractAndUsesConfiguredSeasonAndDatabaseWindows() throws Exception {
        mvc.perform(get("/api/chat-rooms"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].roomId").value(is(List.of("4", "2", "6", "1", "7", "3"))))
                .andExpect(jsonPath("$.data.items[0].length()").value(11))
                .andExpect(jsonPath("$.data.items[0].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.items[1].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.items[3].status").value("LIVE"))
                .andExpect(jsonPath("$.data.items[3].raceId").value("1"))
                .andExpect(jsonPath("$.data.items[3].raceName").value("현재 GP"))
                .andExpect(jsonPath("$.data.items[3].roundNumber").value(1))
                .andExpect(jsonPath("$.data.items[3].participantCount").value(5))
                .andExpect(jsonPath("$.data.items[3].onlineCount").value(0))
                .andExpect(jsonPath("$.data.items[3].weekendStartsAt").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue())).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.meta.eventCursor").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"LIVE,6 1", "SCHEDULED,2", "ARCHIVED,4 7 3"})
    void stateFiltersUseEffectiveTimeAndArchivedOverride(String status, String expected) {
        assertThat(service.rooms(Map.of("status", status), null).items().stream().map(row -> row.roomId()))
                .containsExactly(expected.split(" "));
    }

    @Test
    void seasonFilterAndEmptySeasonWork() throws Exception {
        mvc.perform(get("/api/chat-rooms?season=2025")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].roomId").value(is(List.of("5"))));
        mvc.perform(get("/api/chat-rooms?season=2024")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({"1,true", "2,false", "3,false", "4,false"})
    void detailCanSendReflectsMemberAndCurrentRoomState(String roomId, boolean expected) throws Exception {
        mvc.perform(get("/api/chat-rooms/" + roomId).principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data.canSendMessage").value(expected));
        mvc.perform(get("/api/chat-rooms/" + roomId)).andExpect(jsonPath("$.data.canSendMessage").value(false));
    }

    @Test
    void suspendedMemberMayReadButCannotSendOrDelete() throws Exception {
        mvc.perform(get("/api/chat-rooms/1").principal(() -> "suspended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canSendMessage").value(false));
        mvc.perform(get("/api/chat-rooms/1/messages").principal(() -> "suspended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].canDelete").value(is(List.of(false, false, false, false, false, false, false))));
    }

    @Test
    void onlineConnectionsAreDistinctAndIndependentFromCumulativeParticipation() throws Exception {
        try (var first = presence.connect(1, "user:1"); var second = presence.connect(1, "user:1");
             var anonymous = presence.connect(1, "session:a"); var anotherRoom = presence.connect(2, "user:1")) {
            mvc.perform(get("/api/chat-rooms/1")).andExpect(jsonPath("$.data.onlineCount").value(2))
                    .andExpect(jsonPath("$.data.participantCount").value(5));
            second.close();
            assertThat(presence.counts(List.of(1L, 2L))).containsEntry(1L, 2L).containsEntry(2L, 1L);
            first.close();
            first.close();
            mvc.perform(get("/api/chat-rooms/1")).andExpect(jsonPath("$.data.onlineCount").value(1));
        }
        assertThat(presence.counts(List.of(1L, 2L))).containsEntry(1L, 0L).containsEntry(2L, 0L);
    }

    @Test
    void publicHistoryMasksDeletedAndWithdrawnSendersAndReturnsStableEventCursor() throws Exception {
        mvc.perform(get("/api/chat-rooms/1/messages"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].messageId").value(is(List.of("7", "6", "5", "4", "3", "2", "1"))))
                .andExpect(jsonPath("$.data.length()").value(4)).andExpect(jsonPath("$.data.items[0].length()").value(8))
                .andExpect(jsonPath("$.data.items[0].clientMessageId").value("00000000-0000-0000-0000-000000000007"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-01T10:05:00Z"))
                .andExpect(jsonPath("$.data.items[2].sender.nickname").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.items[3].sender.avatarAssetId").value("avatar_default"))
                .andExpect(jsonPath("$.data.items[4].isDeleted").value(true))
                .andExpect(jsonPath("$.data.items[4].content").value(nullValue())).andExpect(jsonPath("$.data.items[4].sender").value(nullValue()))
                .andExpect(jsonPath("$.data.items[*].canDelete").value(is(List.of(false, false, false, false, false, false, false))))
                .andExpect(jsonPath("$.meta.length()").value(3)).andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.meta.eventCursor").value("evt_1_10"));
    }

    @Test
    void ownerCanDeleteOnlyOwnUndeletedMessagesAndArchivedOwnershipFollowsTR25() throws Exception {
        mvc.perform(get("/api/chat-rooms/1/messages").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].canDelete").value(is(List.of(false, true, false, false, false, false, true))));
        mvc.perform(get("/api/chat-rooms/3/messages").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].canDelete").value(true));
    }

    @Test
    void markersUseActualRecordsDeduplicateEventsAndIgnorePlannedOrWrongRoomRows() {
        var page = service.history(1, Map.of(), null);
        assertThat(page.data().timelineMarkers().stream().map(row -> row.markerId()))
                .containsExactly("session_4_start", "session_1_start", "session_1_end", "flag");
        assertThat(page.data().timelineMarkers().get(1).occurredAt()).isEqualTo(Instant.parse("2026-11-01T10:01:00Z"));
        assertThat(page.data().timelineMarkers().getLast().sessionId()).isNull();
    }

    @Test
    void sessionMarkerIdRemainsStableWhenItsEventRecordArrivesLater() {
        jdbc.update("DELETE FROM stream_events WHERE id=1");
        var before = service.history(1, Map.of(), null).data().timelineMarkers();
        assertThat(before.stream().map(row -> row.markerId())).contains("session_1_start");
        jdbc.update("""
                INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at)
                VALUES(1,4,'SESSION_CHANGED','{"markerId":"arbitrary_event_id","sessionId":"1","label":"실제 전환"}',
                    '2026-11-01 10:01:00','2026-11-01 10:11:00')
                """);
        var after = service.history(1, Map.of(), null).data().timelineMarkers();
        assertThat(after.stream().map(row -> row.markerId())).containsExactlyElementsOf(before.stream().map(row -> row.markerId()).toList());
        assertThat(after.stream().filter(row -> row.markerId().equals("session_1_start")).findFirst().orElseThrow().label()).isEqualTo("실제 전환");
    }

    @Test
    void emptyHistoryReturnsEmptyMarkersAndZeroEventCursor() throws Exception {
        mvc.perform(get("/api/chat-rooms/6/messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.timelineMarkers").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue())).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.meta.eventCursor").value("evt_6_0"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 100})
    void historyTraversalHasNoMessageOrMarkerGapsAndDoesNotRepeatEqualTimeMarkers(int size) {
        var params = new HashMap<>(Map.of("size", Integer.toString(size)));
        var ids = new ArrayList<String>();
        var markerIds = new ArrayList<String>();
        for (int page = 0; page < 20; page++) {
            var result = service.history(1, params, "viewer");
            ids.addAll(result.data().items().stream().map(row -> row.messageId()).toList());
            markerIds.addAll(result.data().timelineMarkers().stream().map(row -> row.markerId()).toList());
            assertThat(result.meta().eventCursor()).isEqualTo("evt_1_10");
            if (!result.data().hasNext()) { assertThat(result.data().nextCursor()).isNull(); break; }
            params.put("cursor", result.data().nextCursor());
        }
        assertThat(ids).containsExactly("7", "6", "5", "4", "3", "2", "1");
        assertThat(markerIds).containsExactlyInAnyOrder("session_4_start", "session_1_start", "session_1_end", "flag").doesNotHaveDuplicates();
    }

    @Test
    void roomsTraverseEndTimeTiesAndRejectChangedFilters() throws Exception {
        var params = new HashMap<>(Map.of("size", "2"));
        var first = service.rooms(params, null);
        var ids = new ArrayList<String>();
        for (int page = 0; page < 10; page++) {
            var result = service.rooms(params, null);
            ids.addAll(result.items().stream().map(row -> row.roomId()).toList());
            if (!result.hasNext()) break;
            params.put("cursor", result.nextCursor());
        }
        assertThat(ids).containsExactly("4", "2", "6", "1", "7", "3");
        for (String query : List.of("size=1", "size=2&season=2025", "size=2&status=LIVE")) {
            mvc.perform(get("/api/chat-rooms?" + query).param("cursor", first.nextCursor())).andExpect(status().isBadRequest());
        }
    }

    @Test
    void newRoomWithAnOldSortKeyCannotEnterAnExistingTraversal() {
        var first = service.rooms(Map.of("size", "2"), null);
        insertRoom(8, Instant.now().plusSeconds(86400 + 100));
        var next = service.rooms(Map.of("size", "2", "cursor", first.nextCursor()), null);
        assertThat(next.items().stream().map(row -> row.roomId())).containsExactly("6", "1");
        assertThat(service.rooms(Map.of(), null).items().stream().map(row -> row.roomId())).contains("8");
    }

    @Test
    void roomListDefaultSizeIs20AndMaximumSizeIs100() {
        for (long id = 10; id < 32; id++) insertRoom(id, Instant.now().plusSeconds(id * 86400));
        var first = service.rooms(Map.of(), null);
        assertThat(first.items()).hasSize(20);
        assertThat(first.hasNext()).isTrue();
        assertThat(service.rooms(Map.of("size", "100"), null).items()).hasSize(28);
    }

    @Test
    void oldHistoryPagesKeepOriginalEventWatermarkWhenNewMessagesAndEventsArrive() {
        var first = service.history(1, Map.of("size", "2"), null);
        jdbc.update("UPDATE chat_rooms SET stream_sequence=12 WHERE id=1");
        jdbc.update("""
                INSERT INTO chat_messages(id,room_id,sender_id,client_message_id,request_hash,content,created_at)
                VALUES(9,1,1,'00000000-0000-0000-0000-000000000009',
                    X'0000000000000000000000000000000000000000000000000000000000000000','새 메시지','2026-11-01 10:03:00')
                """);
        var next = service.history(1, Map.of("size", "2", "cursor", first.data().nextCursor()), null);
        assertThat(next.meta().eventCursor()).isEqualTo("evt_1_10");
        assertThat(next.data().items().stream().map(row -> row.messageId())).containsExactly("5", "4");
        var fresh = service.history(1, Map.of(), null);
        assertThat(fresh.meta().eventCursor()).isEqualTo("evt_1_12");
        assertThat(fresh.data().items().stream().map(row -> row.messageId())).contains("9");
        assertThat(fresh.data().timelineMarkers().stream().map(row -> row.markerId())).contains("after_snapshot");
    }

    @Test
    void defaultMessageSizeIs20AndEmptyPageCanStillHaveActualMarkers() {
        for (long id = 10; id < 32; id++) {
            jdbc.update("INSERT INTO chat_messages(id,room_id,sender_id,client_message_id,request_hash,content,created_at) VALUES(?,1,1,?,?,'추가 메시지',?)",
                    id, "00000000-0000-0000-0000-" + String.format("%012d", id), new byte[32], ChatRepository.utc(Instant.parse("2026-11-01T10:06:00Z")));
        }
        assertThat(service.history(1, Map.of(), null).data().items()).hasSize(20);
        jdbc.update("DELETE FROM chat_messages WHERE room_id=1");
        var empty = service.history(1, Map.of(), null);
        assertThat(empty.data().items()).isEmpty();
        assertThat(empty.data().timelineMarkers()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=OTHER", "status=live", "season=0", "season=abc", "season=10000", "size=0", "size=101",
            "size=-1", "size=1.5", "cursor=", "cursor=invalid", "userId=1"})
    void invalidRoomListInputsReturn400(String query) throws Exception {
        mvc.perform(get("/api/chat-rooms?" + query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "size=abc", "cursor=", "cursor=broken", "status=LIVE", "season=2026"})
    void invalidHistoryInputsReturn400(String query) throws Exception {
        mvc.perform(get("/api/chat-rooms/1/messages?" + query)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "abc", "-1", "9223372036854775808"})
    void invalidRoomIdsReturn400(String id) throws Exception {
        for (String suffix : List.of("", "/messages")) mvc.perform(get("/api/chat-rooms/" + id + suffix)).andExpect(status().isBadRequest());
    }

    @Test
    void absentRoomsReturn404AndDoNotCreateRows() throws Exception {
        for (String suffix : List.of("", "/messages")) mvc.perform(get("/api/chat-rooms/999" + suffix))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms", Long.class)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdrawn", "deleted", "missing", " "})
    void invalidPrincipalsAreNotSilentlyTreatedAsAnonymous(String login) throws Exception {
        for (String path : List.of("/api/chat-rooms", "/api/chat-rooms/1", "/api/chat-rooms/1/messages")) {
            mvc.perform(get(path).principal(() -> login)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void userHeaderDoesNotPersonalizeAndUnverifiedBearerIsRejected() throws Exception {
        mvc.perform(get("/api/chat-rooms/1").header("X-User-Id", "1")).andExpect(jsonPath("$.data.canSendMessage").value(false));
        mvc.perform(get("/api/chat-rooms/1/messages").header("Authorization", "Bearer viewer")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void changedOrMalformedHistoryCursorFieldsReturn400(int field) throws Exception {
        String cursor = service.history(1, Map.of("size", "1"), null).data().nextCursor();
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        parts[field] = switch (field) {
            case 0 -> "rooms-1";
            case 1 -> "3";
            case 2 -> "100";
            case 3, 6 -> Instant.now().plusSeconds(86400).toString();
            case 4, 7 -> "0";
            case 5 -> "999";
            default -> throw new IllegalArgumentException();
        };
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/chat-rooms/1/messages").param("size", "1").param("cursor", changed)).andExpect(status().isBadRequest());
    }

    @Test
    void historyCursorCannotMoveToAnotherRoomOrPageSize() throws Exception {
        String cursor = service.history(1, Map.of("size", "1"), null).data().nextCursor();
        mvc.perform(get("/api/chat-rooms/3/messages").param("size", "1").param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat-rooms/1/messages").param("size", "2").param("cursor", cursor)).andExpect(status().isBadRequest());
    }

    @Test
    void malformedStoredMarkerIsAnUnavailableServerDataError() throws Exception {
        jdbc.update("UPDATE stream_events SET payload='{}' WHERE id=2");
        mvc.perform(get("/api/chat-rooms/1/messages")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHAT_HISTORY_UNAVAILABLE"));
    }

    @Test
    void allReadsPreserveRoomsParticipationStampsMessagesAndEvents() {
        var before = snapshot();
        for (String login : new String[]{null, "viewer", "suspended"}) {
            service.rooms(Map.of(), login);
            service.detail(1, Map.of(), login);
            service.history(1, Map.of(), login);
        }
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    protected void window(long id, Instant opens, Instant closes) {
        jdbc.update("UPDATE chat_rooms SET opens_at=?,closes_at=? WHERE id=?", ChatRepository.utc(opens), ChatRepository.utc(closes), id);
    }

    private void insertRoom(long id, Instant closes) {
        jdbc.update("""
                INSERT INTO races(id,season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at)
                SELECT ?,2026,?,'추가 GP',country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at FROM races WHERE id=1
                """, id, id);
        jdbc.update("INSERT INTO chat_rooms(id,race_id,status,opens_at,closes_at) VALUES(?,?,'SCHEDULED',?,?)",
                id, id, ChatRepository.utc(closes.minusSeconds(3600)), ChatRepository.utc(closes));
    }

    private List<Object> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM chat_rooms ORDER BY id"), jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id"),
                jdbc.queryForList("SELECT * FROM chat_messages ORDER BY id"), jdbc.queryForList("SELECT * FROM race_sessions ORDER BY id"),
                jdbc.queryForList("SELECT id,room_id,stream_sequence,event_type,occurred_at,expires_at FROM stream_events ORDER BY id"),
                jdbc.queryForList("SELECT payload FROM stream_events ORDER BY id", String.class));
    }
}
