package com.boxbox.race;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:boxbox-detail", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:race-schema.sql", "boxbox.market.preparation.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "boxbox.auth.jwt-secret=Ym94Ym94LXJhY2UtYXBpLXRlc3Qta2V5LW5vdC1mb3ItcHJvZHVjdGlvbi0xMjM0NQ=="})
@Transactional
class RaceDetailTest {
    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RaceController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        jdbc.sql("""
                INSERT INTO races
                    (id, season_year, round_number, name, country_name, circuit_name, circuit_key, status, external_key)
                VALUES (42, 2026, 1, 'Grand Prix', 'Country', 'Circuit', '61', 'FINISHED', '1300')
                """).update();
    }

    @Test
    void returnsStoredSessionScheduleAndFinalResult() throws Exception {
        jdbc.sql("""
                INSERT INTO race_sessions (id, race_id, type, name, starts_at)
                VALUES (2, 42, 'RACE', 'Race', TIMESTAMP '2026-11-22 04:00:00'),
                       (1, 42, 'FP1', 'Practice 1', TIMESTAMP '2026-11-20 04:00:00')
                """).update();
        jdbc.sql("""
                INSERT INTO race_results
                    (id, race_id, driver_id, result_version, finish_rank, classification_status, result_status, verified_at)
                VALUES (1, 42, 1, 1, 2, 'FINISHED', 'PROVISIONAL', NULL),
                       (2, 42, 1, 2, 1, 'FINISHED', 'FINAL', TIMESTAMP '2026-11-22 07:00:00')
                """).update();

        mvc.perform(get("/api/races").param("season", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].raceId").value("42"));

        mvc.perform(get("/api/races/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.raceId").value("42"))
                .andExpect(jsonPath("$.data.circuitKey").value("61"))
                .andExpect(jsonPath("$.data.sessions[0].type").value("FP1"))
                .andExpect(jsonPath("$.data.sessions[1].type").value("RACE"))
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].finishRank").value(1))
                .andExpect(jsonPath("$.data.results[0].classificationStatus").value("FINISHED"))
                .andExpect(jsonPath("$.data.results[0].points").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.resultStatus").value("FINAL"))
                .andExpect(jsonPath("$.meta.serverTime").isString());
    }

    @Test
    void returnsEmptyScheduleAndUnavailableResultWhenDataHasNotBeenEntered() throws Exception {
        mvc.perform(get("/api/races/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions").isEmpty())
                .andExpect(jsonPath("$.data.results").isEmpty())
                .andExpect(jsonPath("$.data.resultStatus").value("UNAVAILABLE"));
    }

    @Test
    void returnsNotFoundForUnknownRace() throws Exception {
        mvc.perform(get("/api/races/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RACE_NOT_FOUND"));
    }
}
