package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-chat-stream-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql",
        "boxbox.chat.stream-poll-interval=PT0.02S", "boxbox.chat.stream-heartbeat=PT0.06S", "boxbox.chat.stream-lifetime=PT1S"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/chat-test-data.sql", "/db/chat-write-test-data.sql"})
class ChatStreamApiTests {
    @Autowired protected ChatStreamService stream;
    @Autowired protected ChatMessageService messages;
    @Autowired protected ChatService reads;
    @Autowired protected ChatPresenceRegistry presence;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected PlatformTransactionManager transactions;
    @LocalServerPort int port;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM stream_events");
        jdbc.update("UPDATE chat_rooms SET status='LIVE',stream_sequence=0,opens_at=?,closes_at=?,archived_at=NULL",
                utc(Instant.now().minusSeconds(600)), utc(Instant.now().plusSeconds(600)));
        jdbc.update("UPDATE race_sessions SET actual_starts_at=NULL,actual_ends_at=NULL");
    }

    @Test void openUsesHistoryCursorAndNeverCreatesParticipationOrStamps() {
        var before = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        assertThat(stream.open(1, null, null).sequence()).isZero();
        assertThat(stream.open(1, "evt_1_0", "viewer").identity()).isEqualTo("user:1");
        assertThat(reads.history(1, Map.of(), null).meta().eventCursor()).isEqualTo("evt_1_0");
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"", "evt_2_0", "evt_1_1", "evt_1_-1", "evt_01_0", "evt_1_00", "evt_1_9223372036854775808", "evt_1_0\n", "evil"})
    void malformedWrongRoomOrFutureCursorIs400(String cursor) {
        assertThatThrownBy(() -> stream.open(1, cursor, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    @ParameterizedTest @ValueSource(strings = {"withdrawn", "deleted", "missing", " "})
    void invalidPrincipalCannotOpen(String loginId) {
        assertThatThrownBy(() -> stream.open(1, null, loginId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(401));
    }

    @Test void suspendedMaySubscribeButCannotDelete() {
        messages.send(1, input(), "viewer");
        assertThat(stream.open(1, "evt_1_0", "suspended").identity()).isEqualTo("user:3");
        assertThat(message(stream.poll(1, 0, "suspended"), 0).canDelete()).isFalse();
    }

    @Test void recipientsSeeOwnPermissionsAndStableEnvelope() {
        var sent = messages.send(1, input(), "viewer");
        var owner = stream.poll(1, 0, "viewer");
        assertThat(owner.events()).hasSize(1);
        assertThat(owner.events().getFirst().data().eventId()).isEqualTo("evt_1_1");
        assertThat(owner.events().getFirst().type()).isEqualTo("MESSAGE_CREATED");
        assertThat(message(owner, 0)).isEqualTo(sent);
        assertThat(message(stream.poll(1, 0, "other"), 0).canDelete()).isFalse();
        assertThat(message(stream.poll(1, 0, null), 0).canDelete()).isFalse();
        assertThat(stream.poll(1, 1, null).events()).isEmpty();
    }

    @Test void replayOfCreateAfterDeleteNeverRevealsOriginalTextOrSender() {
        var sent = messages.send(1, input(), "viewer");
        messages.delete(1, Long.parseLong(sent.messageId()), "viewer");
        var batch = stream.poll(1, 0, "viewer");
        assertThat(batch.events()).extracting(ChatStreamService.Event::type).containsExactly("MESSAGE_CREATED", "MESSAGE_DELETED");
        for (int i = 0; i < 2; i++) {
            var deleted = message(batch, i);
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.content()).isNull();
            assertThat(deleted.sender()).isNull();
            assertThat(deleted.canDelete()).isFalse();
        }
    }

    @Test void withdrawnSenderUsesPublicPlaceholderAndAccountChangesAreRechecked() {
        messages.send(1, input(), "viewer");
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=1");
        assertThat(message(stream.poll(1, 0, null), 0).sender().nickname()).isEqualTo("탈퇴한 사용자");
        assertThatThrownBy(() -> stream.poll(1, 0, "viewer")).isInstanceOf(ApiException.class);
    }

    @Test void expiredOrMissingIntermediateEventRequiresResetButCurrentCursorDoesNot() {
        messages.send(1, input(), "viewer");
        messages.send(1, input(), "viewer");
        jdbc.update("UPDATE stream_events SET expires_at=? WHERE stream_sequence=1", utc(Instant.now().minusSeconds(1)));
        assertThat(stream.poll(1, 0, null).resetRequired()).isTrue();
        assertThat(stream.poll(1, 1, null).resetRequired()).isFalse();
        jdbc.update("DELETE FROM stream_events WHERE stream_sequence=2");
        assertThat(stream.poll(1, 1, null).resetRequired()).isTrue();
        assertThat(stream.poll(1, 2, null).resetRequired()).isFalse();
    }

    @Test void batchesPreserveSequenceAcrossPagination() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> { for (int i = 0; i < 103; i++) messages.send(1, input(), "viewer"); });
        var first = stream.poll(1, 0, null);
        var next = stream.poll(1, first.events().getLast().sequence(), null);
        assertThat(first.events()).hasSize(100);
        assertThat(next.events()).extracting(ChatStreamService.Event::sequence).containsExactly(101L, 102L, 103L);
        assertThat(first.latest()).isEqualTo(103);
    }

    @Test void actualSessionsAreRecordedOnceAndMatchHistoryMarkerIds() {
        Instant actual = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE race_sessions SET actual_starts_at=?,actual_ends_at=? WHERE id=1", utc(actual), utc(actual.plusSeconds(1)));
        var batch = stream.poll(1, 0, null);
        assertThat(batch.events()).extracting(ChatStreamService.Event::type).containsExactly("SESSION_CHANGED", "SESSION_CHANGED");
        assertThat(batch.events()).extracting(e -> ((com.boxbox.radio.chat.dto.ChatResponses.Marker) e.data().payload()).markerId())
                .containsExactly("session_1_start", "session_1_end");
        assertThat(stream.poll(1, 2, null).events()).isEmpty();
        assertThat(reads.history(1, Map.of(), null).data().timelineMarkers()).hasSize(2);
        Instant updated = jdbc.queryForObject("SELECT updated_at FROM chat_rooms WHERE id=1", (rs, row) -> ChatRepository.instant(rs, "updated_at"));
        assertThat(updated).isAfter(actual);
    }

    @Test void recordedSessionTransitionIsNotDuplicatedAndFutureActualIsNotPublished() {
        Instant actual = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE race_sessions SET actual_starts_at=?,actual_ends_at=? WHERE id=1", utc(actual), utc(Instant.now().plusSeconds(100)));
        jdbc.update("INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at) VALUES (1,1,'SESSION_CHANGED',?,?,?)",
                "{\"sessionId\":\"1\",\"label\":\"운영 기록\"}", utc(actual), utc(Instant.now().plusSeconds(100)));
        jdbc.update("UPDATE chat_rooms SET stream_sequence=1 WHERE id=1");
        assertThat(stream.poll(1, 0, null).events()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_events", Long.class)).isEqualTo(1);
    }

    @Test void scheduledLiveAndArchivedTransitionsPersistWithOneEventEach() {
        jdbc.update("UPDATE chat_rooms SET status='SCHEDULED' WHERE id=1");
        assertThat(stream.open(1, null, null).sequence()).isZero();
        var live = stream.poll(1, 0, null);
        assertThat(live.events()).hasSize(1);
        assertThat(live.status()).isEqualTo(ChatRoomState.LIVE);
        assertThat(stream.poll(1, 1, null).events()).isEmpty();
        jdbc.update("UPDATE chat_rooms SET closes_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)));
        var archived = stream.poll(1, 1, null);
        assertThat(archived.status()).isEqualTo(ChatRoomState.ARCHIVED);
        assertThat(archived.events()).hasSize(1);
        assertThat(jdbc.queryForMap("SELECT status,archived_at FROM chat_rooms WHERE id=1").get("archived_at")).isNotNull();
        assertThatThrownBy(() -> stream.open(1, null, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CHAT_ROOM_CLOSED"));
    }

    @Test void scheduledRoomCanSubscribeWithoutCreatingFalseActualSession() {
        jdbc.update("UPDATE chat_rooms SET status='SCHEDULED',opens_at=? WHERE id=1", utc(Instant.now().plusSeconds(100)));
        assertThat(stream.open(1, null, null).sequence()).isZero();
        assertThat(stream.poll(1, 0, null).events()).isEmpty();
    }

    @Test void externallyArchivedRoomStillPublishesTerminalStateOnceToExistingConnection() {
        stream.open(1, null, null);
        jdbc.update("UPDATE chat_rooms SET status='ARCHIVED' WHERE id=1");
        var batch = stream.poll(1, 0, null);
        assertThat(batch.status()).isEqualTo(ChatRoomState.ARCHIVED);
        assertThat(batch.events()).extracting(ChatStreamService.Event::type).containsExactly("ROOM_STATUS_CHANGED");
        assertThat(stream.poll(1, 1, null).events()).isEmpty();
    }

    @Test void eventInsertionFailureRollsBackStateAndSequence() {
        jdbc.execute("ALTER TABLE stream_events ADD CONSTRAINT reject_state_event CHECK(event_type<>'ROOM_STATUS_CHANGED')");
        try {
            jdbc.update("UPDATE chat_rooms SET status='SCHEDULED' WHERE id=1");
            assertThatThrownBy(() -> stream.poll(1, 0, null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM chat_rooms WHERE id=1", String.class)).isEqualTo("SCHEDULED");
            assertThat(jdbc.queryForObject("SELECT stream_sequence FROM chat_rooms WHERE id=1", Long.class)).isZero();
        } finally {
            boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                    connection.getMetaData().getDatabaseProductName().equals("MySQL"));
            jdbc.execute("ALTER TABLE stream_events DROP " + (mysql ? "CHECK" : "CONSTRAINT") + " reject_state_event");
        }
    }

    @Test void concurrentSessionAndStatusSynchronizationDoesNotDuplicateEvents() throws Exception {
        jdbc.update("UPDATE chat_rooms SET status='SCHEDULED' WHERE id=1");
        jdbc.update("UPDATE race_sessions SET actual_starts_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                start.await(); return stream.poll(1, 0, null);
            })).toList();
            start.countDown();
            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS).events()).hasSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stream_events", Long.class)).isEqualTo(2);
    }

    @Test void malformedOrCrossRoomStoredReferenceIsNotBroadcast() {
        var sent = messages.send(1, input(), "viewer");
        jdbc.update("UPDATE stream_events SET payload=?", "{\"messageId\":\"" + sent.messageId() + "\",\"roomId\":\"2\"}");
        assertThatThrownBy(() -> stream.poll(1, 0, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("CHAT_STREAM_UNAVAILABLE"));
    }

    @Test void httpErrorsUseExistingJsonContractBeforeStartingStream() throws Exception {
        mvc.perform(get("/api/chat-rooms/404/events").accept("text/event-stream")).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream").header("Authorization", "Bearer viewer"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream").principal(() -> "withdrawn"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat-rooms/1/events").accept("application/json")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat-rooms/1/events")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream;q=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream").param("userId", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream").header("Last-Event-ID", "evt_2_0"))
                .andExpect(status().isBadRequest());
    }

    @Test void realHttpReplaysSseThenHeartbeatAndReleasesPresenceWithoutParticipation() throws Exception {
        messages.send(1, input(), "viewer");
        var participants = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        var response = http("evt_1_0");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).isEqualTo("no-store");
        assertThat(response.body()).contains("id:evt_1_1", "event:MESSAGE_CREATED", "\"canDelete\":false", "event:ONLINE_COUNT_CHANGED", ":heartbeat", "event:RECONNECT_REQUIRED");
        assertThat(presence.counts(List.of(1L)).get(1L)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(participants);
    }

    @Test void realHttpExpiredCursorEmitsResetWithoutAdvancingSseId() throws Exception {
        messages.send(1, input(), "viewer");
        jdbc.update("UPDATE stream_events SET expires_at=?", utc(Instant.now().minusSeconds(1)));
        var response = http("evt_1_0");
        assertThat(response.body()).contains("event:RESET_REQUIRED", "\"historyUrl\":\"/api/chat-rooms/1/messages\"");
        assertThat(response.body()).doesNotContain("id:evt_", "event:MESSAGE_CREATED");
    }

    @Test void realHttpInitialCheckpointAllowsReplayOfMessageBetweenConnections() throws Exception {
        messages.send(1, input(), "viewer");
        var first = http(null);
        assertThat(first.body()).contains("event:STREAM_READY", "id:evt_1_1").doesNotContain("event:MESSAGE_CREATED");
        var next = messages.send(1, input(), "viewer");
        var reconnect = http("evt_1_1");
        assertThat(reconnect.body()).contains("id:evt_1_2", "\"messageId\":\"" + next.messageId() + "\"");
    }

    @Test void realHttpDeliversNewCommitThenArchiveTransitionAndCloses() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat-rooms/1/events"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").header("Last-Event-ID", "evt_1_0").build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var body = response.body()) {
                assertThat(reads.detail(1, Map.of(), null).onlineCount()).isEqualTo(1);
                var sent = messages.send(1, input(), "viewer");
                jdbc.update("UPDATE chat_rooms SET closes_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)));
                String wire = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(wire).contains("event:MESSAGE_CREATED", "\"messageId\":\"" + sent.messageId() + "\"",
                        "event:ROOM_STATUS_CHANGED", "\"status\":\"ARCHIVED\"").doesNotContain("RECONNECT_REQUIRED");
            }
            assertThat(presence.counts(List.of(1L)).get(1L)).isZero();
        }
    }

    @Test void existingConnectionReflectsNewSuspensionInMessagePermissions() {
        stream.open(1, null, "viewer");
        messages.send(1, input(), "viewer");
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=1");
        assertThat(message(stream.poll(1, 0, "viewer"), 0).canDelete()).isFalse();
    }

    protected HttpResponse<String> http(String cursor) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat-rooms/1/events"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream");
            if (cursor != null) request.header("Last-Event-ID", cursor);
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    protected Map<String,Object> input() { return Map.of("clientMessageId", UUID.randomUUID().toString(), "content", "SSE 메시지 🏎️"); }
    private static java.time.LocalDateTime utc(Instant at) { return ChatRepository.utc(at); }
    private static com.boxbox.radio.chat.dto.ChatResponses.Message message(ChatStreamService.Batch batch, int i) {
        return (com.boxbox.radio.chat.dto.ChatResponses.Message) batch.events().get(i).data().payload();
    }
}
