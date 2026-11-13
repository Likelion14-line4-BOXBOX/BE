package com.boxbox.radio.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.boxbox.common.api.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 미리 전체 ERD를 적용한 로컬 전용 DB만 사용한다. 공통 DB 사용 금지. */
@EnabledIfEnvironmentVariable(named = "RUN_POST_MYSQL_TESTS", matches = "true")
class PostMySqlTests extends PostApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_post_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("POST_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("POST_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test
    void oneUploadCannotBeClaimedByTwoPostsConcurrently() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> createAfterStart(start));
            var second = pool.submit(() -> createAfterStart(start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=12", String.class)).isEqualTo("ATTACHED");
    }

    private String createAfterStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try {
            service.create(10, "viewer", Map.of("title", "동시 작성", "content", "본문", "mediaIds", List.of("12")));
            return "OK";
        } catch (ApiException exception) {
            return exception.getCode();
        }
    }
}
