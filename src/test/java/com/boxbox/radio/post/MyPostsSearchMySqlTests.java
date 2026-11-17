package com.boxbox.radio.post;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #18의 요약 조회 추출 회귀 검증도 새 전용 DB에서 수행해 기존 검증 DB를 보존한다. */
@EnabledIfEnvironmentVariable(named = "RUN_MY_POSTS_MYSQL_TESTS", matches = "true")
class MyPostsSearchMySqlTests extends PostSearchApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_my_posts_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MY_POSTS_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MY_POSTS_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
