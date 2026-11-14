package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_CHAT_SSE_MYSQL_TESTS", matches = "true")
class ChatStreamMySqlTests extends ChatStreamApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_chat_sse_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CHAT_SSE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CHAT_SSE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test void pollCannotObserveUncommittedMessageAndRollbackLeavesNoEventToSend() throws Exception {
        assertVisibility(false);
    }

    @Test void pollSeesMessageAndEventTogetherOnlyAfterCommit() throws Exception {
        assertVisibility(true);
    }

    private void assertVisibility(boolean commit) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var inserted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                messages.send(1, input(), "viewer");
                inserted.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("writer release timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                if (!commit) status.setRollbackOnly();
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var reader = executor.submit(() -> stream.poll(1, 0, null));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_rooms'
                            """, Long.class) > 0;
                    if (!waiting) Thread.sleep(10);
                }
                assertThat(waiting).as("poll reached the uncommitted room lock").isTrue();
                assertThat(reader.isDone()).isFalse();
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            assertThat(reader.get(5, TimeUnit.SECONDS).events()).hasSize(commit ? 1 : 0);
            assertThat(jdbc.queryForObject("SELECT stream_sequence FROM chat_rooms WHERE id=1", Long.class)).isEqualTo(commit ? 1L : 0L);
        }
    }
}
