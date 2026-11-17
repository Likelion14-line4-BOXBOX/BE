package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_CHAT_WRITE_MYSQL_TESTS", matches = "true")
class ChatMessageMySqlTests extends ChatMessageApiTests {
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        ChatWriteReadMySqlTests.mysqlProperties(registry);
    }

    @Test
    void senderWaitingForRoomLockChecksCommittedClosingTimeAfterAcquiringLock() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var outcome = new AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM chat_rooms WHERE id=1 FOR UPDATE", Long.class);
                outcome.set(pool.submit(() -> {
                    try { messages.send(1, body("종료 경계"), "viewer"); return "CREATED"; }
                    catch (com.boxbox.common.api.ApiException exception) { return exception.getCode(); }
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_rooms'
                            """, Long.class) > 0;
                    if (!waiting) { try { Thread.sleep(20); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
                }
                assertThat(waiting).as("sender reached the room lock").isTrue();
                jdbc.update("UPDATE chat_rooms SET closes_at=? WHERE id=1", ChatRepository.utc(Instant.now().minusSeconds(1)));
            });
            assertThat(outcome.get().get(5, TimeUnit.SECONDS)).isEqualTo("CHAT_ROOM_CLOSED");
            assertThat(sequence()).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Long.class)).isEqualTo(8);
        }
    }
}
