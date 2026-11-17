package com.boxbox.garage.activity;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 전체 ERD가 적용된 내 활동 목록 전용 격리 DB에서만 데이터를 초기화한다. */
@EnabledIfEnvironmentVariable(named = "RUN_MY_POSTS_MYSQL_TESTS", matches = "true")
class MyPostsMySqlTests extends MyPostsApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:13306/boxbox_my_posts_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MY_POSTS_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MY_POSTS_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
