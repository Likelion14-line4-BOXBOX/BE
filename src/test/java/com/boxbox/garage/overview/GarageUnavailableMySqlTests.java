package com.boxbox.garage.overview;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "RUN_GARAGE_MYSQL_TESTS", matches = "true")
class GarageUnavailableMySqlTests extends GarageUnavailableApiTests {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:13306/boxbox_garage_verify?connectionTimeZone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("GARAGE_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("GARAGE_MYSQL_PASSWORD", ""));
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
