package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-chat-write-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql",
        "boxbox.chat.event-retention=PT10M"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/chat-test-data.sql", "/db/chat-write-test-data.sql"})
class ChatMessageApiTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ChatMessageService messages;
    @Autowired protected ChatService reads;
    @Autowired protected JsonMapper json;

    @BeforeEach
    void windows() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        window(1, now.minusSeconds(86400), now.plusSeconds(86400));
        window(2, now.plusSeconds(86400), now.plusSeconds(2 * 86400));
        window(3, now.minusSeconds(2 * 86400), now.minusSeconds(86400));
        window(4, now.minusSeconds(86400), now.plusSeconds(86400));
        window(6, now.minusSeconds(86400), now.plusSeconds(86400));
    }

    @Test
    void sendReturnsContractAndAtomicallyStoresFirstMessageAndEvent() throws Exception {
        var participant = jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=1 AND user_id=1");
        String id = UUID.randomUUID().toString().toUpperCase();
        String body = json.writeValueAsString(Map.of("clientMessageId", id, "content", "\u2003 안녕 🏎️ \n"));
        String response = mvc.perform(post("/api/chat-rooms/1/messages").principal(() -> "viewer")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(8)).andExpect(jsonPath("$.data.clientMessageId").value(id.toLowerCase()))
                .andExpect(jsonPath("$.data.roomId").value("1")).andExpect(jsonPath("$.data.sender.userId").value("1"))
                .andExpect(jsonPath("$.data.sender.nickname").value("회원 하나")).andExpect(jsonPath("$.data.content").value("안녕 🏎️"))
                .andExpect(jsonPath("$.data.isDeleted").value(false)).andExpect(jsonPath("$.data.canDelete").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String messageId = json.readTree(response).path("data").path("messageId").asString();
        var stored = jdbc.queryForMap("SELECT * FROM chat_messages WHERE id=?", messageId);
        assertThat((byte[]) stored.get("request_hash")).isEqualTo(MessageDigest.getInstance("SHA-256").digest("안녕 🏎️".getBytes(StandardCharsets.UTF_8)));
        Instant storedTime = jdbc.queryForObject("SELECT created_at FROM chat_messages WHERE id=?", (rs, row) -> ChatRepository.instant(rs, "created_at"), messageId);
        assertThat(Instant.parse(json.readTree(response).path("data").path("createdAt").asString())).isEqualTo(storedTime);
        assertThat(count("chat_messages")).isEqualTo(9);
        assertThat(sequence()).isEqualTo(11);
        var after = jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=1 AND user_id=1");
        assertThat(after.get("first_message_at")).isEqualTo(stored.get("created_at"));
        assertThat(after).containsEntry("first_joined_at", participant.get("first_joined_at"))
                .containsEntry("last_joined_at", participant.get("last_joined_at"))
                .containsEntry("stamp_recorded_at", null).containsEntry("stamp_trigger_type", null);
        assertEvent(11, "MESSAGE_CREATED", messageId, id.toLowerCase());
        assertThat(reads.detail(1, Map.of(), null).onlineCount()).isZero();
    }

    @Test
    void firstSendCreatesParticipationButNeverStampAndRetryDoesNotTouchAnything() {
        var input = body("첫 참여");
        var sent = messages.send(1, input, "newcomer");
        var state = snapshot();
        var retried = messages.send(1, Map.of("clientMessageId", input.get("clientMessageId"), "content", "  첫 참여\n"), "newcomer");
        assertThat(retried).isEqualTo(sent);
        assertUnchanged(state);
        var participant = jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=1 AND user_id=6");
        assertThat(participant.get("first_joined_at")).isEqualTo(participant.get("first_message_at"));
        assertThat(participant.get("stamp_recorded_at")).isNull();
        assertThat(reads.detail(1, Map.of(), "newcomer").participantCount()).isEqualTo(6);
    }

    @Test
    void existingFirstMessageAndStampArePreservedAcrossNewMessages() {
        jdbc.update("UPDATE chat_participants SET first_message_at=first_joined_at,stamp_recorded_at=first_joined_at,stamp_trigger_type='MESSAGE' WHERE room_id=1 AND user_id=1");
        var before = jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=1 AND user_id=1");
        messages.send(1, body("추가 메시지"), "viewer");
        assertThat(jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=1 AND user_id=1")).isEqualTo(before);
    }

    @Test
    void reusedUuidWithDifferentContentConflictsAndUuidScopeIsUserAndRoom() {
        var input = body("원문");
        var sent = messages.send(1, input, "viewer");
        var state = snapshot();
        expectCode(() -> messages.send(1, Map.of("clientMessageId", input.get("clientMessageId"), "content", "다른 본문"), "viewer"), "CLIENT_MESSAGE_CONFLICT");
        assertUnchanged(state);
        assertThat(messages.send(1, input, "other").messageId()).isNotEqualTo(sent.messageId());
        assertThat(messages.send(6, input, "viewer").messageId()).isNotEqualTo(sent.messageId());
    }

    @Test
    void exactRetryWorksAfterRoomClosesButNewSendDoesNot() {
        var input = body("응답 유실 재시도");
        var sent = messages.send(1, input, "viewer");
        jdbc.update("UPDATE chat_rooms SET status='ARCHIVED' WHERE id=1");
        var state = snapshot();
        assertThat(messages.send(1, input, "viewer")).isEqualTo(sent);
        expectCode(() -> messages.send(1, body("새 메시지"), "viewer"), "CHAT_ROOM_CLOSED");
        assertUnchanged(state);
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 3, 4})
    void scheduledEndedAndExplicitlyArchivedRoomsRejectNewMessages(long room) throws Exception {
        var state = snapshot();
        mvc.perform(post("/api/chat-rooms/" + room + "/messages").principal(() -> "viewer")
                .contentType("application/json").content(json.writeValueAsString(body("전송"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CHAT_ROOM_CLOSED"));
        assertUnchanged(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "withdrawn", "deleted"})
    void invalidAccountsCannotMutate(String login) throws Exception {
        var state = snapshot();
        mvc.perform(post("/api/chat-rooms/1/messages").principal(() -> login).contentType("application/json").content(json.writeValueAsString(body("전송"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/chat-rooms/1/messages/1").principal(() -> login)).andExpect(status().isUnauthorized());
        assertUnchanged(state);
    }

    @Test
    void anonymousBearerAndSpoofedHeadersCannotMutate() throws Exception {
        var state = snapshot();
        for (String header : List.of("X-User-Id", "Authorization")) {
            String value = header.equals("Authorization") ? "Bearer viewer" : "1";
            mvc.perform(post("/api/chat-rooms/1/messages").header(header, value).contentType("application/json").content(json.writeValueAsString(body("전송"))))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/chat-rooms/1/messages/1").header(header, value)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/chat-rooms/1/messages").contentType("application/json").content(json.writeValueAsString(body("전송"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/chat-rooms/1/messages/1")).andExpect(status().isUnauthorized());
        assertUnchanged(state);
    }

    @Test
    void suspendedAccountCannotSendOrDelete() throws Exception {
        var state = snapshot();
        mvc.perform(post("/api/chat-rooms/1/messages").principal(() -> "suspended").contentType("application/json").content(json.writeValueAsString(body("전송"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CHAT_RESTRICTED"));
        mvc.perform(delete("/api/chat-rooms/1/messages/1").principal(() -> "suspended"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CHAT_RESTRICTED"));
        assertUnchanged(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"content\":\"text\"}",
            "{\"clientMessageId\":\"1-1-1-1-1\",\"content\":\"text\"}",
            "{\"clientMessageId\":\"00000000-0000-0000-0000-000000000010\",\"content\":null}",
            "{\"clientMessageId\":\"00000000-0000-0000-0000-000000000010\",\"content\":123}",
            "{\"clientMessageId\":\"00000000-0000-0000-0000-000000000010\",\"content\":\"x\",\"userId\":1}"})
    void invalidBodyReturnsCommonErrorAndChangesNothing(String content) throws Exception {
        var state = snapshot();
        mvc.perform(post("/api/chat-rooms/1/messages").principal(() -> "viewer").contentType("application/json").content(content))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertUnchanged(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t\u2003", "\uD800", "\uDC00"})
    void emptyAndMalformedUnicodeIsRejected(String content) {
        expectCode(() -> messages.send(1, body(content), "viewer"), "INVALID_REQUEST");
    }

    @Test
    void contentUsesCodePointsAndTrimsBeforeFiveHundredCharacterLimit() {
        String emoji = "🏎".repeat(500);
        assertThat(messages.send(1, body(" " + emoji + "\n"), "viewer").content()).isEqualTo(emoji);
        var state = snapshot();
        expectCode(() -> messages.send(1, body(emoji + "가"), "viewer"), "INVALID_REQUEST");
        assertUnchanged(state);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "abc,1", "9223372036854775808,1", "1,0", "1,abc"})
    void invalidPathIdsAreRejected(String room, String message) throws Exception {
        mvc.perform(delete("/api/chat-rooms/" + room + "/messages/" + message).principal(() -> "viewer")).andExpect(status().isBadRequest());
    }

    @Test
    void roomAndMessageExistenceAndOwnershipHaveDistinctErrors() throws Exception {
        mvc.perform(post("/api/chat-rooms/999/messages").principal(() -> "viewer").contentType("application/json").content(json.writeValueAsString(body("없음"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
        mvc.perform(delete("/api/chat-rooms/999/messages/1").principal(() -> "viewer"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
        for (String path : List.of("1/messages/999", "6/messages/1")) {
            mvc.perform(delete("/api/chat-rooms/" + path).principal(() -> "viewer"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MESSAGE_NOT_FOUND"));
        }
        for (long id : List.of(1L, 3L)) {
            mvc.perform(delete("/api/chat-rooms/1/messages/" + id).principal(() -> "other"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_MESSAGE_AUTHOR"));
        }
    }

    @Test
    void deleteMasksHistoryKeepsIdentityAndRetryDoesNotAppendEvents() throws Exception {
        var input = body("삭제될 원문");
        var sent = messages.send(1, input, "viewer");
        var before = jdbc.queryForMap("SELECT * FROM chat_messages WHERE id=?", sent.messageId());
        var first = reads.history(1, Map.of("size", "1"), "viewer");
        mvc.perform(delete("/api/chat-rooms/1/messages/" + sent.messageId()).principal(() -> "viewer"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var stored = jdbc.queryForMap("SELECT * FROM chat_messages WHERE id=?", sent.messageId());
        assertThat(stored).containsEntry("content", null).containsEntry("created_at", before.get("created_at"))
                .containsEntry("client_message_id", before.get("client_message_id"));
        assertThat((byte[]) stored.get("request_hash")).isEqualTo((byte[]) before.get("request_hash"));
        assertThat(stored.get("deleted_at")).isNotNull();
        var history = reads.history(1, Map.of(), "viewer");
        var deleted = history.data().items().getFirst();
        assertThat(deleted.messageId()).isEqualTo(sent.messageId());
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.sender()).isNull();
        assertThat(deleted.content()).isNull();
        assertThat(deleted.canDelete()).isFalse();
        assertThat(history.meta().eventCursor()).isEqualTo("evt_1_12");
        assertThat(reads.history(1, Map.of("size", "1", "cursor", first.data().nextCursor()), "viewer").meta().eventCursor()).isEqualTo("evt_1_11");
        assertEvent(12, "MESSAGE_DELETED", sent.messageId(), sent.clientMessageId());
        var state = snapshot();
        mvc.perform(delete("/api/chat-rooms/1/messages/" + sent.messageId()).principal(() -> "viewer")).andExpect(status().isNoContent());
        expectCode(() -> messages.send(1, input, "viewer"), "CLIENT_MESSAGE_CONFLICT");
        assertUnchanged(state);
    }

    @Test
    void ownMessagesCanBeDeletedAfterRoomEndsAndPreviouslyDeletedRetryIsNoOp() throws Exception {
        mvc.perform(delete("/api/chat-rooms/3/messages/8").principal(() -> "viewer")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT stream_sequence FROM chat_rooms WHERE id=3", Long.class)).isEqualTo(2);
        var state = snapshot();
        mvc.perform(delete("/api/chat-rooms/1/messages/3").principal(() -> "viewer")).andExpect(status().isNoContent());
        assertUnchanged(state);
    }

    @Test
    void eventInsertFailureRollsBackNewMessageNewParticipantAndRoomSequence() throws Exception {
        occupyNextSequence();
        var state = snapshot();
        mvc.perform(post("/api/chat-rooms/1/messages").principal(() -> "newcomer").contentType("application/json").content(json.writeValueAsString(body("롤백"))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
        assertUnchanged(state);
    }

    @Test
    void eventInsertFailureRollsBackDeletionAndSequence() throws Exception {
        occupyNextSequence();
        var state = snapshot();
        mvc.perform(delete("/api/chat-rooms/1/messages/1").principal(() -> "viewer"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
        assertUnchanged(state);
    }

    @Test
    void concurrentIdenticalSendsHaveOneMessageOneEventAndStableResponse() throws Exception {
        var input = body("중복 전송");
        var results = parallel(IntStream.range(0, 8).mapToObj(i -> (Callable<String>) () -> messages.send(1, input, "viewer").messageId()).toList());
        assertThat(results).containsOnly(results.getFirst());
        assertThat(count("chat_messages")).isEqualTo(9);
        assertThat(sequence()).isEqualTo(11);
        assertThat(count("stream_events")).isEqualTo(5);
    }

    @Test
    void concurrentConflictingPayloadsGiveOneWinnerAndOneConflict() throws Exception {
        String id = UUID.randomUUID().toString();
        var results = parallel(List.of(sendOutcome(id, "A"), sendOutcome(id, "B")));
        assertThat(results).containsExactlyInAnyOrder("CREATED", "CLIENT_MESSAGE_CONFLICT");
        assertThat(sequence()).isEqualTo(11);
        assertThat(count("chat_messages")).isEqualTo(9);
    }

    @Test
    void concurrentSendersGetDistinctContiguousRoomSequences() throws Exception {
        parallel(IntStream.range(0, 8).mapToObj(i -> (Callable<String>) () -> messages.send(1, body("전송 " + i), i % 2 == 0 ? "viewer" : "other").messageId()).toList());
        assertThat(sequence()).isEqualTo(18);
        assertThat(jdbc.queryForList("SELECT stream_sequence FROM stream_events WHERE room_id=1 AND stream_sequence>10 ORDER BY stream_sequence", Long.class))
                .containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L);
    }

    @Test
    void concurrentDeletesAppendOneEventAndRetryCannotResurrect() throws Exception {
        var input = body("동시 삭제");
        var sent = messages.send(1, input, "viewer");
        parallel(IntStream.range(0, 8).mapToObj(i -> (Callable<String>) () -> { messages.delete(1, Long.parseLong(sent.messageId()), "viewer"); return "OK"; }).toList());
        assertThat(sequence()).isEqualTo(12);
        var state = snapshot();
        expectCode(() -> messages.send(1, input, "viewer"), "CLIENT_MESSAGE_CONFLICT");
        assertUnchanged(state);
    }

    @Test
    void concurrentRetryAndDeleteNeverRestoreDeletedContent() throws Exception {
        var input = body("재시도와 삭제 경쟁");
        var sent = messages.send(1, input, "viewer");
        var results = parallel(List.of(
                () -> { messages.delete(1, Long.parseLong(sent.messageId()), "viewer"); return "DELETED"; },
                () -> { try { return messages.send(1, input, "viewer").messageId(); }
                    catch (ApiException exception) { return exception.getCode(); } }));
        assertThat(results.get(0)).isEqualTo("DELETED");
        assertThat(results.get(1)).isIn(sent.messageId(), "CLIENT_MESSAGE_CONFLICT");
        assertThat(sequence()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id=?", String.class, sent.messageId())).isNull();
    }

    protected void window(long id, Instant start, Instant end) {
        jdbc.update("UPDATE chat_rooms SET opens_at=?,closes_at=? WHERE id=?", ChatRepository.utc(start), ChatRepository.utc(end), id);
    }
    protected Map<String, Object> body(String content) { return Map.of("clientMessageId", UUID.randomUUID().toString(), "content", content); }
    protected long sequence() { return jdbc.queryForObject("SELECT stream_sequence FROM chat_rooms WHERE id=1", Long.class); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM chat_rooms ORDER BY id"), jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id"),
                jdbc.queryForList("SELECT * FROM chat_messages ORDER BY id"), jdbc.queryForList("SELECT * FROM stream_events ORDER BY id"));
    }
    private void assertUnchanged(List<List<Map<String, Object>>> before) { assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before); }
    protected void expectCode(Runnable task, String code) { assertThatThrownBy(task::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code); }
    private void occupyNextSequence() {
        jdbc.update("INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at) VALUES (1,11,'MESSAGE_CREATED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    private void assertEvent(long sequence, String type, String messageId, String clientId) {
        var row = jdbc.queryForMap("SELECT * FROM stream_events WHERE room_id=1 AND stream_sequence=?", sequence);
        String payload = jdbc.queryForObject("SELECT payload FROM stream_events WHERE room_id=1 AND stream_sequence=?", String.class, sequence);
        assertThat(json.readTree(payload)).isEqualTo(json.valueToTree(Map.of("messageId", messageId, "roomId", "1", "clientMessageId", clientId)));
        assertThat(row.get("event_type")).isEqualTo(type);
        assertThat(row.get("published_at")).isNull();
        var times = jdbc.queryForObject("SELECT occurred_at,expires_at FROM stream_events WHERE room_id=1 AND stream_sequence=?",
                (rs, i) -> List.of(ChatRepository.instant(rs, "occurred_at"), ChatRepository.instant(rs, "expires_at")), sequence);
        assertThat(times.get(1)).isEqualTo(times.get(0).plusSeconds(600));
    }
    private Callable<String> sendOutcome(String id, String content) {
        return () -> { try { messages.send(1, Map.of("clientMessageId", id, "content", content), "viewer"); return "CREATED"; }
            catch (ApiException exception) { return exception.getCode(); } };
    }
    private List<String> parallel(List<Callable<String>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = tasks.stream().map(task -> pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return task.call(); })).toList();
            start.countDown();
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        }
    }
}
