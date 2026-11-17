package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.boxbox.common.api.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamConnectionTests {
    private final ChatStreamService service = mock(ChatStreamService.class);
    private final ChatPresenceRegistry presence = new ChatPresenceRegistry();

    @Test void sameMemberAndAnonymousSessionDeduplicateAndCallbacksReleaseOnlyTheirToken() throws Exception {
        var hub = hub(3, 5000);
        try {
            var a = new RecordingEmitter(); var b = new RecordingEmitter(); var guest = new RecordingEmitter();
            hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, a);
            hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, b);
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", guest);
            assertThat(count()).isEqualTo(2);
            a.completion.run(); a.completion.run();
            assertThat(count()).isEqualTo(2);
            b.timeout.run();
            assertThat(count()).isEqualTo(1);
            guest.error.accept(new IOException());
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void capacityRejectsCleanlyAndIsReusableAfterDisconnect() {
        var hub = hub(1, 5000);
        try {
            var a = new RecordingEmitter();
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", a);
            assertThatThrownBy(() -> hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, new RecordingEmitter()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CHAT_STREAM_BUSY"));
            verify(service, never()).participate(anyLong(), nullable(String.class), any(BooleanSupplier.class));
            assertThat(count()).isEqualTo(1);
            a.completion.run();
            var b = new RecordingEmitter();
            hub.start(1, null, new ChatStreamService.Open(0, null), "other", b);
            assertThat(count()).isEqualTo(1);
        } finally { hub.shutdown(); }
        assertThat(count()).isZero();
    }

    @Test void anonymousMultipleTabsShareServerSessionIdentity() {
        var hub = hub(2, 5000);
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "same", new RecordingEmitter());
            hub.start(1, null, new ChatStreamService.Open(0, null), "same", new RecordingEmitter());
            assertThat(count()).isEqualTo(1);
        } finally { hub.shutdown(); }
    }

    @Test void ioFailureReleasesWithoutCompletingContainerManagedErrorAgain() throws Exception {
        var hub = hub(1, 1000);
        var emitter = new RecordingEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException { throw new IOException("disconnect"); }
        };
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", emitter);
            await(() -> count() == 0);
            assertThat(emitter.completed.get()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void heartbeatAndEphemeralEventsDoNotAdvanceLastEventIdAndLifetimeEndsConnection() throws Exception {
        var hub = hub(1, 100);
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", emitter);
            await(() -> emitter.completed.get() == 1);
            String wire = String.join("", emitter.parts);
            assertThat(wire).contains(":heartbeat", "ONLINE_COUNT_CHANGED", "RECONNECT_REQUIRED", "id:evt_1_0");
            assertThat(wire.split("id:evt_", -1)).hasSize(2); // 시작 체크포인트 외 비영속 이벤트에는 id가 없다.
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void expiredReplayRequestsRestResetAndClosesWithoutCursorAdvance() throws Exception {
        var hub = hub(1, 1000);
        when(service.poll(1, 0, null)).thenReturn(new ChatStreamService.Batch(List.of(), 100, ChatRoomState.LIVE, true));
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", emitter);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).contains("RESET_REQUIRED").doesNotContain("id:");
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void accountInvalidationDuringConnectionEmitsAuthRequiredAndCloses() throws Exception {
        var hub = hub(1, 1000);
        when(service.poll(1, 0, "viewer")).thenThrow(ApiException.unauthorized());
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, emitter);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).contains("AUTH_REQUIRED");
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void credentialsExpiringDuringPollCannotReceiveReadyOrEarnStamp() throws Exception {
        var hub = hub(1, 1000);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(service.poll(1, 0, "viewer")).thenAnswer(call -> {
            valid.set(false);
            return new ChatStreamService.Batch(List.of(), 0, ChatRoomState.LIVE, false);
        });
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, emitter, valid::get);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).contains("AUTH_REQUIRED").doesNotContain("STREAM_READY");
            verify(service, never()).participate(anyLong(), nullable(String.class), any(BooleanSupplier.class));
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void tokenExpiringAfterReadyEndsConnectionAndReleasesPresence() throws Exception {
        var hub = hub(1, 1000);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, "viewer", new ChatStreamService.Open(0, "user:1"), null, emitter, valid::get);
            await(() -> String.join("", emitter.parts).contains("STREAM_READY"));
            valid.set(false);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).contains("AUTH_REQUIRED");
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    @Test void internalFailureReturnsGenericStreamErrorWithoutExceptionText() throws Exception {
        var hub = hub(1, 1000);
        when(service.poll(1, 0, null)).thenThrow(new IllegalStateException("private database details"));
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", emitter);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).contains("STREAM_ERROR").doesNotContain("private database details");
        } finally { hub.shutdown(); }
    }

    @Test void slowReceiverDoesNotBlockAnotherConnectionAndShutdownReleasesBoth() throws Exception {
        var hub = hub(2, 100);
        var entered = new CountDownLatch(1);
        var slow = new RecordingEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        };
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "slow", slow);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var fast = new RecordingEmitter();
            hub.start(1, null, new ChatStreamService.Open(0, null), "fast", fast);
            await(() -> fast.completed.get() == 1);
            assertThat(count()).isEqualTo(1);
        } finally { hub.shutdown(); }
        assertThat(count()).isZero();
    }

    @Test void committedBatchesAdvanceOnlyAfterSuccessfulSendAndArchiveClosesAfterDrain() throws Exception {
        var hub = hub(1, 1000);
        var first = new ChatStreamService.Event(1, "MESSAGE_CREATED", new ChatStreamService.Envelope("evt_1_1", java.time.Instant.now(), java.util.Map.of()));
        var second = new ChatStreamService.Event(2, "ROOM_STATUS_CHANGED", new ChatStreamService.Envelope("evt_1_2", java.time.Instant.now(), java.util.Map.of()));
        when(service.poll(1, 0, null)).thenReturn(new ChatStreamService.Batch(List.of(first), 2, ChatRoomState.ARCHIVED, false));
        when(service.poll(1, 1, null)).thenReturn(new ChatStreamService.Batch(List.of(second), 2, ChatRoomState.ARCHIVED, false));
        var emitter = new RecordingEmitter();
        try {
            hub.start(1, null, new ChatStreamService.Open(0, null), "session", emitter);
            await(() -> emitter.completed.get() == 1);
            assertThat(String.join("", emitter.parts)).containsSubsequence("id:evt_1_1", "id:evt_1_2");
            assertThat(count()).isZero();
        } finally { hub.shutdown(); }
    }

    private ChatStreamHub hub(int capacity, int milliseconds) {
        when(service.poll(anyLong(), anyLong(), nullable(String.class)))
                .thenReturn(new ChatStreamService.Batch(List.of(), 0, ChatRoomState.LIVE, false));
        return new ChatStreamHub(service, presence, Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofMillis(milliseconds), capacity);
    }
    private long count() { return presence.counts(List.of(1L)).get(1L); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static class RecordingEmitter extends SseEmitter {
        final List<String> parts = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();
        Runnable completion = () -> { }; Runnable timeout = () -> { }; Consumer<Throwable> error = ignored -> { };
        @Override public void send(SseEventBuilder builder) throws IOException { builder.build().forEach(item -> parts.add(String.valueOf(item.getData()))); }
        @Override public void complete() { completed.incrementAndGet(); completion.run(); }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
    }
}
