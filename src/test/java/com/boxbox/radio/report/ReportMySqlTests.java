package com.boxbox.radio.report;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boxbox.common.api.ApiException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_REPORT_MYSQL_TESTS", matches = "true")
class ReportMySqlTests extends ReportApiTests {
    @DynamicPropertySource static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_report_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("REPORT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("REPORT_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @ParameterizedTest @CsvSource({"POST,true", "COMMENT,true", "CHAT_MESSAGE,true", "POST,false", "COMMENT,false", "CHAT_MESSAGE,false"})
    void reportWaitsForActualDeletionLockAndChecksCommitOrRollback(String type, boolean commit) throws Exception {
        String path = switch (type) {
            case "POST" -> "/api/posts/1";
            case "COMMENT" -> "/api/comments/1";
            default -> "/api/chat-rooms/1/messages/1";
        };
        String table = type.equals("CHAT_MESSAGE") ? "chat_rooms" : "posts";
        var applied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                try {
                    mvc.perform(delete(path).principal(() -> "viewer")).andExpect(status().isNoContent());
                    applied.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("delete release timeout");
                    if (!commit) status.setRollbackOnly();
                } catch (Exception error) { throw new IllegalStateException(error); }
            }));
            assertThat(applied.await(5, TimeUnit.SECONDS)).isTrue();
            var reporting = executor.submit(() -> {
                try { reports.create("other", body(type, "1", "SPAM")); return "CREATED"; }
                catch (ApiException error) { return error.getCode(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?
                            """, Long.class, table) > 0;
                    if (!waiting) Thread.sleep(10);
                }
                assertThat(waiting).as("report reached existing delete API's target lock").isTrue();
                assertThat(reporting.isDone()).isFalse();
            } finally { release.countDown(); }
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(reporting.get(5, TimeUnit.SECONDS)).isEqualTo(commit ? "REPORT_TARGET_NOT_FOUND" : "CREATED");
        }
        assertThat(count()).isEqualTo(commit ? 0 : 1);
    }
}
