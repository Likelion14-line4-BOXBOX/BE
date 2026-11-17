package com.boxbox.race;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:boxbox-schedule", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:race-schema.sql", "boxbox.market.preparation.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "boxbox.auth.jwt-secret=Ym94Ym94LXJhY2UtYXBpLXRlc3Qta2V5LW5vdC1mb3ItcHJvZHVjdGlvbi0xMjM0NQ=="})
@Transactional
class RaceScheduleServiceTest {
    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RaceScheduleService service;

    @Test
    void filtersByStatusBeforePagingWithoutChangingRoundNumbers() {
        jdbc.sql("""
                INSERT INTO races (id, season_year, round_number, name, country_name, circuit_name, circuit_key, status)
                VALUES (3, 2026, 3, 'Third GP', 'Country', 'Circuit', '61', 'SCHEDULED'),
                       (1, 2026, 1, 'First GP', 'Country', 'Circuit', '61', 'CANCELLED'),
                       (2, 2026, 2, 'Second GP', 'Country', 'Circuit', '61', 'SCHEDULED')
                """).update();

        var firstPage = service.getRaces(2026, RaceScheduleService.RaceStatus.SCHEDULED, 0, 1);

        assertEquals(2, firstPage.items().getFirst().roundNumber());
        assertEquals("2", firstPage.items().getFirst().raceId());
        assertEquals("2026-SCHEDULED-2", firstPage.nextCursor());
        assertTrue(firstPage.hasNext());

        var secondPage = service.getRaces(2026, RaceScheduleService.RaceStatus.SCHEDULED, 2, 1);

        assertEquals(3, secondPage.items().getFirst().roundNumber());
        assertNull(secondPage.nextCursor());
        assertFalse(secondPage.hasNext());
    }
}
