package com.boxbox.garage.overview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.boxbox.garage.overview.dto.GarageMarketSummary;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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

@EnabledIfEnvironmentVariable(named = "RUN_GARAGE_MYSQL_TESTS", matches = "true")
class GarageMySqlTests extends GarageApiTests {
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("GARAGE_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:13306/boxbox_garage_verify?connectionTimeZone=UTC&characterEncoding=UTF-8"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("GARAGE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("GARAGE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test void profilePrivacyAndStampReadsUseOneSnapshotAndNextRequestObservesCommit() throws Exception {
        var queried = new CountDownLatch(1); var release = new CountDownLatch(1);
        var summary = new GarageMarketSummary(BigInteger.valueOf(500), null, null, Instant.parse("2026-11-09T07:00:00Z"));
        when(market.find(2)).thenAnswer(invocation -> {
            queried.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("reader release timeout");
            return Optional.of(summary);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = executor.submit(() -> garage.get("viewer", "2", Map.of()));
            assertThat(queried.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update("UPDATE users SET nickname='변경된닉네임',show_market_assets=FALSE WHERE id=2");
                    jdbc.update("INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,stamp_recorded_at,stamp_trigger_type) VALUES (1,2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'JOIN')");
                });
            } finally { release.countDown(); }
            var before = read.get(5, TimeUnit.SECONDS);
            assertThat(before.profile().nickname()).isEqualTo("TakenName");
            assertThat(before.marketSummary()).isEqualTo(summary);
            assertThat(before.stampSummary().watchedCount()).isZero();
        }
        clearInvocations(market);
        var after = garage.get("viewer", "2", Map.of());
        assertThat(after.profile().nickname()).isEqualTo("변경된닉네임");
        assertThat(after.marketSummary()).isNull();
        assertThat(after.stampSummary().watchedCount()).isEqualTo(1);
        verifyNoInteractions(market);
    }
}
