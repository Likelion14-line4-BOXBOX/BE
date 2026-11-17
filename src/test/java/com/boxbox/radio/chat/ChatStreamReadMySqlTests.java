package com.boxbox.radio.chat;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #24 전용 DB에서 REST 이력/커서 계약을 회귀 검증한다. */
@EnabledIfEnvironmentVariable(named = "RUN_CHAT_SSE_MYSQL_TESTS", matches = "true")
class ChatStreamReadMySqlTests extends ChatReadApiTests {
    @DynamicPropertySource static void mysqlProperties(DynamicPropertyRegistry registry) { ChatStreamMySqlTests.mysqlProperties(registry); }
}
