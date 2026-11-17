package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import com.boxbox.garage.stamp.RaceStampService;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-stamp-join-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/post-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql",
        "boxbox.chat.stream-poll-interval=PT0.01S", "boxbox.chat.stream-lifetime=PT0.5S"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/chat-test-data.sql", "/db/chat-write-test-data.sql"})
class ChatStampApiTests {
    @Test void expiredCredentialsCannotGrantJoinStamp() {
        assertThatThrownBy(() -> stream.participate(1, "newcomer", () -> false))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("UNAUTHORIZED"));
        assertThat(stampCount()).isZero();
    }
    @Autowired protected ChatStreamHub hub;
    @Autowired protected ChatStreamService stream;
    @Autowired protected ChatMessageService messages;
    @Autowired protected ChatService reads;
    @Autowired protected RaceStampService stamps;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected ChatPresenceRegistry presence;
    private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM stream_events");
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_participants");
        jdbc.update("UPDATE race_sessions SET actual_starts_at=NULL,actual_ends_at=NULL");
        jdbc.update("UPDATE chat_rooms SET status='LIVE',stream_sequence=0,opens_at=?,closes_at=?,archived_at=NULL",
                utc(Instant.now().minusSeconds(60)), utc(Instant.now().plusSeconds(600)));
    }

    @AfterEach void closeConnections() throws Exception {
        emitters.forEach(RecordingEmitter::complete);
        await(() -> presence.counts(List.of(1L, 2L)).values().stream().allMatch(n -> n == 0));
    }

    @Test void liveJoinWithoutAnyMessageAwardsStampAndCountsParticipant() throws Exception {
        join(1, "newcomer");
        await(() -> stampCount() == 1);
        var row = participant(1, 6);
        assertThat(row.get("FIRST_MESSAGE_AT")).isNull();
        assertThat(row.get("STAMP_TRIGGER_TYPE")).isEqualTo("JOIN");
        assertThat(stamps.list("viewer", "6", Map.of("season", "2026")).watchedCount()).isEqualTo(1);
        assertThat(reads.detail(1, Map.of(), null).participantCount()).isEqualTo(1);
        assertThat(count("chat_messages")).isZero();
        assertThat(count("stream_events")).isZero();
    }

    @Test void authenticatedSseControllerUsesJoinPathWithoutPostingMessage() throws Exception {
        var result = mvc.perform(get("/api/chat-rooms/1/events").accept("text/event-stream").principal(() -> "newcomer"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(3000);
        assertThat(result.getResponse().getContentAsString()).contains("STREAM_READY");
        assertThat(stampCount()).isEqualTo(1);
        assertThat(count("chat_messages")).isZero();
    }

    @Test void reconnectKeepsFirstStampAndFirstJoinAndOnlyUpdatesLastJoin() throws Exception {
        var first = join(1, "newcomer");
        await(() -> stampCount() == 1);
        var before = participant(1, 6);
        first.complete();
        join(1, "newcomer");
        await(() -> !participant(1, 6).get("LAST_JOINED_AT").equals(before.get("LAST_JOINED_AT")));
        var after = participant(1, 6);
        assertThat(after.get("STAMP_RECORDED_AT")).isEqualTo(before.get("STAMP_RECORDED_AT"));
        assertThat(after.get("FIRST_JOINED_AT")).isEqualTo(before.get("FIRST_JOINED_AT"));
        assertThat(count("chat_participants")).isEqualTo(1);
    }

    @Test void simultaneousTabsAwardOnlyOnceAndDifferentMembersStayIndependent() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                start.await(); return join(1, i % 2 == 0 ? "newcomer" : "other");
            })).toList();
            start.countDown();
            for (var result : results) result.get(5, TimeUnit.SECONDS);
        }
        await(() -> stampCount() == 2);
        assertThat(count("chat_participants")).isEqualTo(2);
        assertThat(stamps.list("viewer", "6", Map.of()).watchedCount()).isEqualTo(1);
        assertThat(stamps.list("viewer", "2", Map.of()).watchedCount()).isEqualTo(1);
    }

    @Test void scheduledConnectionAwardsOnlyWhenItRemainsConnectedIntoLive() throws Exception {
        jdbc.update("UPDATE chat_rooms SET status='SCHEDULED',opens_at=? WHERE id=1", utc(Instant.now().plusSeconds(100)));
        var emitter = join(1, "newcomer");
        await(() -> emitter.wire().contains("STREAM_READY"));
        assertThat(stampCount()).isZero();
        jdbc.update("UPDATE chat_rooms SET opens_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)));
        await(() -> stampCount() == 1);
        assertThat(emitter.wire()).contains("STREAM_READY");
    }

    @Test void leavingScheduledRoomDoesNotAwardLater() throws Exception {
        jdbc.update("UPDATE chat_rooms SET status='SCHEDULED',opens_at=? WHERE id=1", utc(Instant.now().plusSeconds(100)));
        var emitter = join(1, "newcomer");
        await(() -> emitter.wire().contains("STREAM_READY"));
        emitter.complete();
        jdbc.update("UPDATE chat_rooms SET opens_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)));
        assertThat(count("chat_participants")).isZero();
    }

    @Test void anonymousConnectionAndEveryReadEndpointNeverAward() throws Exception {
        var guest = join(1, null);
        await(() -> guest.wire().contains("STREAM_READY"));
        stream.open(1, null, "newcomer");
        reads.rooms(Map.of(), "newcomer");
        reads.detail(1, Map.of(), "newcomer");
        reads.history(1, Map.of(), "newcomer");
        stamps.list("viewer", "6", Map.of());
        assertThat(count("chat_participants")).isZero();
    }

    @Test void archivedAndExpiredRoomsCannotAwardIncludingPostHandshakeStateChange() {
        jdbc.update("UPDATE chat_rooms SET status='ARCHIVED' WHERE id=1");
        assertThatThrownBy(() -> join(1, "newcomer")).isInstanceOf(ApiException.class);
        assertThat(stream.participate(1, "newcomer")).isFalse();
        jdbc.update("UPDATE chat_rooms SET status='LIVE',closes_at=? WHERE id=2", utc(Instant.now().minusSeconds(1)));
        assertThat(stream.participate(2, "newcomer")).isFalse();
        assertThat(count("chat_participants")).isZero();
    }

    @Test void invalidAndExpiredReplayNeverAward() throws Exception {
        assertThatThrownBy(() -> hub.subscribe(1, "evt_2_0", "newcomer", null)).isInstanceOf(ApiException.class);
        var message = messages.send(1, input(), "viewer");
        jdbc.update("UPDATE stream_events SET expires_at=?", utc(Instant.now().minusSeconds(1)));
        var emitter = new RecordingEmitter(); emitters.add(emitter);
        hub.start(1, "newcomer", stream.open(1, "evt_1_0", "newcomer"), null, emitter);
        await(() -> emitter.wire().contains("RESET_REQUIRED"));
        assertThat(stampCount()).isZero();
        assertThat(participant(1, 1).get("STAMP_RECORDED_AT")).isNull();
        assertThat(message.messageId()).isNotBlank();
    }

    @Test void failedReadySendNeverAwards() throws Exception {
        var emitter = new RecordingEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException {
                String wire = builder.build().stream().map(item -> String.valueOf(item.getData())).collect(java.util.stream.Collectors.joining());
                if (wire.contains("STREAM_READY")) throw new IOException("disconnected before ready");
                super.send(builder);
            }
        };
        emitters.add(emitter);
        hub.start(1, "newcomer", stream.open(1, null, "newcomer"), null, emitter);
        await(() -> presence.counts(List.of(1L)).get(1L) == 0);
        assertThat(stampCount()).isZero();
    }

    @Test void accountWithdrawnDuringHandshakeCannotAward() throws Exception {
        var emitter = new RecordingEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException {
                String wire = builder.build().stream().map(item -> String.valueOf(item.getData())).collect(java.util.stream.Collectors.joining());
                if (wire.contains("STREAM_READY")) jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=6");
                super.send(builder);
            }
        };
        emitters.add(emitter);
        hub.start(1, "newcomer", stream.open(1, null, "newcomer"), null, emitter);
        await(() -> emitter.wire().contains("AUTH_REQUIRED"));
        assertThat(stampCount()).isZero();
    }

    @Test void messageAloneDoesNotAwardAndDeleteDoesNotRetractJoinedStamp() throws Exception {
        var message = messages.send(1, input(), "newcomer");
        var firstMessage = participant(1, 6).get("FIRST_MESSAGE_AT");
        assertThat(stampCount()).isZero();
        join(1, "newcomer");
        await(() -> stampCount() == 1);
        var recorded = participant(1, 6).get("STAMP_RECORDED_AT");
        messages.delete(1, Long.parseLong(message.messageId()), "newcomer");
        assertThat(participant(1, 6).get("STAMP_RECORDED_AT")).isEqualTo(recorded);
        assertThat(participant(1, 6).get("FIRST_MESSAGE_AT")).isEqualTo(firstMessage);
        assertThat(stamps.list("viewer", "6", Map.of()).watchedCount()).isEqualTo(1);
    }

    @Test void existingAutomaticStampAndParticipationTimesAreNotReplaced() {
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,first_message_at,stamp_recorded_at,stamp_trigger_type) VALUES (1,6,?,?,?,?, 'MESSAGE')",
                utc(old), utc(old), utc(old), utc(old));
        assertThat(stream.participate(1, "newcomer")).isTrue();
        var row = participant(1, 6);
        assertThat(row.get("STAMP_RECORDED_AT")).isEqualTo(utc(old));
        assertThat(row.get("STAMP_TRIGGER_TYPE")).isEqualTo("MESSAGE");
        assertThat(row.get("FIRST_JOINED_AT")).isEqualTo(utc(old));
    }

    @Test void suspendedReadOnlyMemberCanParticipateWithoutSending() throws Exception {
        join(1, "suspended");
        await(() -> stampCount() == 1);
        assertThat(participant(1, 3).get("FIRST_MESSAGE_AT")).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "unknown", "withdrawn", "deleted"})
    void invalidatedAccountsCannotRecordParticipation(String loginId) {
        assertThatThrownBy(() -> stream.participate(1, loginId)).isInstanceOf(ApiException.class);
        assertThat(stampCount()).isZero();
    }

    @Test void transactionRollbackRemovesNewStampAndParticipantTogether() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(stream.participate(1, "newcomer")).isTrue();
            status.setRollbackOnly();
        });
        assertThat(count("chat_participants")).isZero();
        assertThat(stream.participate(1, null)).isFalse();
    }

    @Test void stampFailureRollsBackExistingLastJoinAndDoesNotCreateMessageOrEvent() {
        messages.send(1, input(), "newcomer");
        var before = participant(1, 6);
        jdbc.execute("ALTER TABLE chat_participants ADD CONSTRAINT reject_join_stamp CHECK(stamp_trigger_type IS NULL OR stamp_trigger_type<>'JOIN')");
        try {
            assertThatThrownBy(() -> stream.participate(1, "newcomer")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(participant(1, 6)).isEqualTo(before);
            assertThat(count("chat_messages")).isEqualTo(1);
            assertThat(count("stream_events")).isEqualTo(1);
        } finally {
            boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("MySQL"));
            jdbc.execute("ALTER TABLE chat_participants DROP " + (mysql ? "CHECK" : "CONSTRAINT") + " reject_join_stamp");
        }
    }

    protected RecordingEmitter join(long room, String login) {
        var emitter = new RecordingEmitter(); emitters.add(emitter);
        hub.start(room, login, stream.open(room, null, login), login == null ? "guest" : null, emitter);
        return emitter;
    }
    protected long stampCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_participants WHERE stamp_recorded_at IS NOT NULL", Long.class); }
    protected long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    protected Map<String, Object> participant(long room, long user) {
        // JDBC 드라이버별 컬럼 대소문자와 Timestamp/LocalDateTime 차이를 정규화한다.
        var row = jdbc.queryForMap("SELECT * FROM chat_participants WHERE room_id=? AND user_id=?", room, user);
        var normalized = new java.util.HashMap<String, Object>();
        row.forEach((key, value) -> normalized.put(key.toUpperCase(java.util.Locale.ROOT),
                value instanceof java.sql.Timestamp time ? time.toLocalDateTime() : value));
        return normalized;
    }
    protected static Map<String, Object> input() { return Map.of("clientMessageId", UUID.randomUUID().toString(), "content", "참여 후 메시지"); }
    protected static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    protected static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    protected static class RecordingEmitter extends SseEmitter {
        final List<String> parts = new CopyOnWriteArrayList<>();
        Runnable completion = () -> { };
        @Override public void send(SseEventBuilder builder) throws IOException { builder.build().forEach(item -> parts.add(String.valueOf(item.getData()))); }
        @Override public void complete() { completion.run(); }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        @Override public void onTimeout(Runnable callback) { }
        @Override public void onError(java.util.function.Consumer<Throwable> callback) { }
        String wire() { return String.join("", parts); }
    }
}
