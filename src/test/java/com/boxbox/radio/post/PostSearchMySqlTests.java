package com.boxbox.radio.post;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 전체 ERD가 적용된 로컬 전용 DB. 공통 DB 사용 금지. */
@EnabledIfEnvironmentVariable(named = "RUN_POST_SEARCH_MYSQL_TESTS", matches = "true")
class PostSearchMySqlTests extends PostSearchApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_search_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("POST_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("POST_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
