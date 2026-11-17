package com.boxbox.garage.overview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.boxbox.market.portfolio.MarketPortfolioService;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_GARAGE_MYSQL_TESTS", matches = "true")
class GarageMarketMySqlTests extends GarageMarketIntegrationTests {
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean MarketPortfolioService portfolio;
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("GARAGE_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:13306/boxbox_garage_market_verify?connectionTimeZone=UTC&characterEncoding=UTF-8"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("GARAGE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("GARAGE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Test void marketAndProfileJoinTheSameSnapshotAndNextRequestSeesCommittedChanges() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("market read release timeout");
            return call.callRealMethod();
        }).when(portfolio).summary(eq(2L), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = executor.submit(() -> garage.get("viewer", "2", Map.of()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update("UPDATE users SET nickname='연동변경회원',coin_balance=100000,show_market_assets=FALSE WHERE id=2");
                    jdbc.update("UPDATE market_stocks SET current_price=10000 WHERE id=1");
                    jdbc.update("INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,stamp_recorded_at,stamp_trigger_type) VALUES(1,2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'JOIN')");
                });
            } finally { release.countDown(); }
            var before = read.get(5, TimeUnit.SECONDS);
            assertThat(before.profile().nickname()).isEqualTo("TakenName");
            assertThat(before.marketSummary().totalAssets()).isEqualTo(BigInteger.valueOf(670000));
            assertThat(before.stampSummary().watchedCount()).isZero();
        }
        var after = garage.get("other", "2", Map.of());
        assertThat(after.profile().nickname()).isEqualTo("연동변경회원");
        assertThat(after.marketSummary().totalAssets()).isEqualTo(BigInteger.valueOf(170000));
        assertThat(after.stampSummary().watchedCount()).isEqualTo(1);
        assertThat(garage.get("viewer", "2", Map.of()).marketSummary()).isNull();
    }
}
