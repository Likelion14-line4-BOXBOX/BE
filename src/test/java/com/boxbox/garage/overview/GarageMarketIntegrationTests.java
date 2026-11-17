package com.boxbox.garage.overview;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-garage-market-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "boxbox.market.preparation.enabled=false",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql,classpath:db/garage-market-schema.sql"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql({"/db/garage-market-cleanup.sql", "/db/garage-cleanup.sql", "/db/profile-test-data.sql",
        "/db/garage-test-data.sql", "/db/garage-market-test-data.sql"})
@Sql(scripts = "/db/garage-market-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class GarageMarketIntegrationTests {
    @Autowired GarageService garage;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void ownerUsesRealCurrentValuationSeasonBaselineAndPublishedSeasonRank() throws Exception {
        var before = Instant.now();
        var result = garage.get("viewer", "1", Map.of());
        assertThat(result.marketSummary().totalAssets()).isEqualTo(BigInteger.valueOf(1020000));
        assertThat(result.marketSummary().returnRate()).isEqualByComparingTo("2");
        assertThat(result.marketSummary().rank()).isEqualTo(12L);
        assertThat(result.marketSummary().snapshotAt()).isBetween(before, Instant.now());
        // 최애 차량 번호가 없어도 금액 평가에는 영향을 주지 않는다.
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.favoriteDriver.number").value(nullValue()))
                .andExpect(jsonPath("$.data.marketSummary.totalAssets").value(1020000));
    }

    @Test void publicOtherUsesTargetsHoldingsAndSeasonBaseline() {
        var result = garage.get("viewer", "2", Map.of());
        assertThat(result.isOwner()).isFalse();
        assertThat(result.marketSummary().totalAssets()).isEqualTo(BigInteger.valueOf(670000));
        assertThat(result.marketSummary().returnRate()).isEqualByComparingTo("34");
        assertThat(result.marketSummary().rank()).isEqualTo(25L);
    }

    @Test void noHoldingsOrMissingAndZeroBaselineAreNormalNullableSummaries() {
        jdbc.update("DELETE FROM positions WHERE user_id=1");
        jdbc.update("DELETE FROM user_season_baselines WHERE user_id=1 AND season_year=2026");
        jdbc.update("DELETE FROM ranking_entries WHERE user_id=1");
        var missing = garage.get("viewer", "1", Map.of()).marketSummary();
        assertThat(missing.totalAssets()).isEqualTo(BigInteger.valueOf(900000));
        assertThat(missing.returnRate()).isNull(); assertThat(missing.rank()).isNull();
        jdbc.update("INSERT INTO user_season_baselines(user_id,season_year,starting_assets,joined_at) VALUES (1,2026,0,CURRENT_TIMESTAMP)");
        assertThat(garage.get("viewer", "1", Map.of()).marketSummary().returnRate()).isNull();
        jdbc.update("UPDATE user_season_baselines SET starting_assets=1000000 WHERE user_id=1 AND season_year=2026");
        assertThat(garage.get("viewer", "1", Map.of()).marketSummary().returnRate()).isEqualByComparingTo("-10");
    }

    @Test void noCurrentRoundReturnsActualCashWithNullRateAndRank() {
        jdbc.update("UPDATE market_rounds SET status='CANCELLED'");
        var summary = garage.get("viewer", "1", Map.of()).marketSummary();
        assertThat(summary.totalAssets()).isEqualTo(BigInteger.valueOf(900000));
        assertThat(summary.returnRate()).isNull(); assertThat(summary.rank()).isNull();
    }

    @Test void latestPublishedSnapshotWithoutTargetDoesNotFallBackToOlderRank() {
        jdbc.update("UPDATE ranking_snapshots SET status='PUBLISHED' WHERE id=2");
        jdbc.update("DELETE FROM ranking_entries WHERE snapshot_id=2");
        assertThat(garage.get("viewer", "1", Map.of()).marketSummary().rank()).isNull();
    }

    @Test void largeAssetAndUnsignedRankValuesRemainExactJsonNumbers() throws Exception {
        jdbc.update("UPDATE positions SET quantity=200000000000000 WHERE user_id=1 AND stock_id=1");
        jdbc.update("UPDATE ranking_entries SET `rank`=3000000000 WHERE snapshot_id=1 AND user_id=1");
        var expected = new BigInteger("12000000000000900000");
        assertThat(garage.get("viewer", "1", Map.of()).marketSummary().totalAssets()).isEqualTo(expected);
        mvc.perform(get("/api/users/1/garage").principal(() -> "viewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.marketSummary.totalAssets").value(org.hamcrest.Matchers.equalTo(expected), BigInteger.class))
                .andExpect(jsonPath("$.data.marketSummary.rank").value(3000000000L));
    }

    @Test void readsDoNotMutateWalletHoldingsPrivacyOrStamps() {
        var users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        var positions = jdbc.queryForList("SELECT * FROM positions ORDER BY user_id,stock_id");
        var participants = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        garage.get("viewer", "1", Map.of()); garage.get("viewer", "2", Map.of());
        assertThat(garage.get("other", "1", Map.of()).marketSummary()).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(users);
        assertThat(jdbc.queryForList("SELECT * FROM positions ORDER BY user_id,stock_id")).isEqualTo(positions);
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(participants);
    }
}
