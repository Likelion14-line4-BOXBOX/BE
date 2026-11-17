package com.boxbox.radio.reaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 전체 ERD를 적용한 로컬 반응 검증 전용 DB에서만 데이터를 초기화한다. */
@EnabledIfEnvironmentVariable(named = "RUN_REACTION_MYSQL_TESTS", matches = "true")
class ReactionMySqlTests extends ReactionApiTests {
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_reaction_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("REACTION_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("REACTION_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test
    void commentCountIncludesAnotherCommitMadeWhileWaitingForPostLock() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM posts WHERE id=1 FOR UPDATE", Long.class);
                locked.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("lock release timed out");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                jdbc.update("INSERT INTO comment_likes(comment_id,user_id) VALUES(2,1)");
                return true;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> service.likeComment(2, "other"));
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='posts'
                            """, Long.class) > 0;
                    if (waiting) break;
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertThat(waiting).as("second request is waiting after finding comment.post_id").isTrue();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS).likeCount()).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }
}
