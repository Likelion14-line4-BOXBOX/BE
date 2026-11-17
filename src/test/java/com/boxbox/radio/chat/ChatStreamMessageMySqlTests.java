package com.boxbox.radio.chat;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #24 전용 DB에서 메시지 쓰기 계약을 회귀 검증한다. */
@EnabledIfEnvironmentVariable(named = "RUN_CHAT_SSE_MYSQL_TESTS", matches = "true")
class ChatStreamMessageMySqlTests extends ChatMessageApiTests {
    @DynamicPropertySource static void mysqlProperties(DynamicPropertyRegistry registry) { ChatStreamMySqlTests.mysqlProperties(registry); }
}
