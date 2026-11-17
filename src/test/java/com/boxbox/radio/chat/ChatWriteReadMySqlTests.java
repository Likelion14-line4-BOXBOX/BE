package com.boxbox.radio.chat;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #22 전용 DB에서 기존 읽기 계약을 검증한다. #20 검증 DB는 초기화하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_CHAT_WRITE_MYSQL_TESTS", matches = "true")
class ChatWriteReadMySqlTests extends ChatReadApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_chat_write_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CHAT_WRITE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CHAT_WRITE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
