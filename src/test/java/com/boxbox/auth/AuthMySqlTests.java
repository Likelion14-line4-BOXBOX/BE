package com.boxbox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ERD 전체를 적용한 전용 로컬 DB에서만 실행한다. */
@EnabledIfEnvironmentVariable(named = "RUN_AUTH_MYSQL_TESTS", matches = "true")
class AuthMySqlTests extends AuthApiTests {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_auth_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AUTH_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Override void dropLedgerFailure() { jdbc.execute("ALTER TABLE coin_ledger DROP CHECK auth_test_failure"); }

    @Test void nicknameDuplicateUsesActualMySqlCollation() throws Exception {
        signup("accentone", "1234abcd", "Café", 201);
        var result = signup("accenttwo", "1234abcd", "cafe", 409);
        assertThat(result.getResponse().getContentAsString()).contains("NICKNAME_ALREADY_EXISTS");
        assertThat(count("coin_ledger")).isEqualTo(1);
    }
}
