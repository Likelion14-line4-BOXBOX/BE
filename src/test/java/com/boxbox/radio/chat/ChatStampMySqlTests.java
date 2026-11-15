package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.*;

import com.boxbox.common.api.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_STAMP_MYSQL_TESTS", matches = "true")
class ChatStampMySqlTests extends ChatStampApiTests {
    @Test void credentialsExpiringDuringMemberLockWaitCannotGrantStamp() throws Exception {
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id=6 FOR UPDATE", Long.class);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var join = pool.submit(() -> stream.participate(1, "newcomer", valid::get));
            try {
                assertThatThrownBy(() -> join.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                valid.set(false);
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> join.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        } finally { release.countDown(); }
        assertThat(stampCount()).isZero();
    }
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_stamp_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("STAMP_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("STAMP_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test void parallelJoinsAndMessagesUseSameLockOrderWithoutDuplicateStamp() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = java.util.stream.IntStream.range(0, 12).mapToObj(i -> executor.submit(() -> {
                start.await();
                if (i % 2 == 0) stream.participate(1, "newcomer");
                else messages.send(1, input(), "newcomer");
                return true;
            })).toList();
            start.countDown();
            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(stampCount()).isEqualTo(1);
        assertThat(count("chat_participants")).isEqualTo(1);
        assertThat(count("chat_messages")).isEqualTo(6);
        assertThat(count("stream_events")).isEqualTo(6);
        assertThat(participant(1, 6).get("STAMP_TRIGGER_TYPE")).isEqualTo("JOIN");
        assertThat(participant(1, 6).get("FIRST_MESSAGE_AT")).isNotNull();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void roomClosingIsRecheckedAfterActualLockWait(boolean commit) throws Exception {
        assertAfterLock("chat_rooms", "UPDATE chat_rooms SET closes_at=? WHERE id=1", utc(Instant.now().minusSeconds(1)), commit, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void accountWithdrawalIsRecheckedAfterActualLockWait(boolean commit) throws Exception {
        assertAfterLock("users", "UPDATE users SET status=? WHERE id=6", "WITHDRAWN", commit, true);
    }

    private void assertAfterLock(String table, String update, Object value, boolean commit, boolean account) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update(update, value);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                if (!commit) status.setRollbackOnly();
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var join = executor.submit(() -> {
                try { return stream.participate(1, "newcomer") ? "JOINED" : "CLOSED"; }
                catch (ApiException error) { return error.getCode(); }
            });
            try {
                await(() -> jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?
                        """, Long.class, table) > 0);
                assertThat(join.isDone()).isFalse();
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            assertThat(join.get(5, TimeUnit.SECONDS)).isEqualTo(commit ? (account ? "UNAUTHORIZED" : "CLOSED") : "JOINED");
        }
        assertThat(stampCount()).isEqualTo(commit ? 0 : 1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readSeesStampOnlyAfterCommitAndNeverSeesRolledBackParticipation(boolean commit) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                stream.participate(1, "newcomer");
                inserted.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                if (!commit) status.setRollbackOnly();
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThat(stamps.list("viewer", "6", Map.of()).watchedCount()).isZero(); }
            finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
        }
        assertThat(stamps.list("viewer", "6", Map.of()).watchedCount()).isEqualTo(commit ? 1 : 0);
        assertThat(count("chat_participants")).isEqualTo(commit ? 1 : 0);
    }

    @Test void sameUserCanJoinTwoRacesConcurrentlyAndEachCountsOnce() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> stream.participate(1, "newcomer"));
            var b = executor.submit(() -> stream.participate(2, "newcomer"));
            assertThat(a.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(stamps.list("viewer", "6", Map.of()).watchedCount()).isEqualTo(2);
    }
}
