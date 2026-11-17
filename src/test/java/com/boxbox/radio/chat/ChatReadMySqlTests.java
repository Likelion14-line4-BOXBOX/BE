package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_CHAT_READ_MYSQL_TESTS", matches = "true")
class ChatReadMySqlTests extends ChatReadApiTests {
    @MockitoSpyBean ChatRepository spiedRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_chat_read_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CHAT_READ_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CHAT_READ_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test
    void historyContentAndEventCursorShareSnapshotAcrossConcurrentDeletion() {
        doAnswer(invocation -> {
            long max = (long) invocation.callRealMethod();
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE chat_messages SET content=NULL,deleted_at=UTC_TIMESTAMP(6) WHERE id=1");
                    jdbc.update("UPDATE chat_rooms SET stream_sequence=12 WHERE id=1");
                })).get(5, TimeUnit.SECONDS);
            }
            return max;
        }).when(spiedRepository).maxMessageId(1);
        var result = service.history(1, Map.of(), null);
        assertThat(result.meta().eventCursor()).isEqualTo("evt_1_10");
        assertThat(result.data().items().getLast().isDeleted()).isFalse();
        assertThat(result.data().items().getLast().content()).isEqualTo("첫 메시지");
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id=1", String.class)).isNull();
    }
}
