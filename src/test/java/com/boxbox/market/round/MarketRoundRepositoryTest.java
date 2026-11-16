package com.boxbox.market.round;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRoundRepositoryTest {
    private MarketRoundRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        repository = new MarketRoundRepository(new NamedParameterJdbcTemplate(source));
        jdbc.execute("""
                CREATE TABLE races (id BIGINT PRIMARY KEY, season_year INT,
                    round_number INT, name VARCHAR(120), status VARCHAR(24))
                """);
        jdbc.execute("""
                CREATE TABLE market_rounds (id BIGINT PRIMARY KEY, race_id BIGINT, season_year INT,
                    status VARCHAR(24), open_at DATETIME(6), close_at DATETIME(6), publish_at DATETIME(6),
                    ranking_published_at DATETIME(6), rule_version VARCHAR(40), status_version BIGINT)
                """);
        jdbc.execute("CREATE TABLE chat_rooms (id BIGINT PRIMARY KEY, race_id BIGINT)");
    }

    @Test
    void prefersOpenRoundUntilExclusiveCloseThenSelectsNextScheduledRound() {
        addRound(1, 2026, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(2, 2026, "SCHEDULED", "2026-11-18 00:00:00", "2026-11-20 00:00:00");
        addRound(3, 2026, "SCHEDULED", "2026-11-21 00:00:00", "2026-11-22 00:00:00");
        addRound(4, 2026, "CANCELLED", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(5, 2025, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        jdbc.update("INSERT INTO chat_rooms (id, race_id) VALUES (50, 1)");

        var open = repository.current(2026, Instant.parse("2026-11-16T00:00:00Z")).orElseThrow();
        assertEquals("1", open.id());
        assertEquals("50", open.chatRoomId());
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), open.openAt());

        var upcoming = repository.current(2026, Instant.parse("2026-11-17T00:00:00Z")).orElseThrow();
        assertEquals("2", upcoming.id());
        assertNull(upcoming.chatRoomId());
    }

    @Test
    void omitsCancelledRaceAndRoundWithoutRequiredTimes() {
        addRound(1, 2026, "OPEN", "2026-11-16 00:00:00", "2026-11-17 00:00:00");
        addRound(2, 2026, "SCHEDULED", null, "2026-11-20 00:00:00");
        jdbc.update("UPDATE races SET status = 'CANCELLED' WHERE id = 1");

        assertTrue(repository.current(2026, Instant.parse("2026-11-16T00:00:00Z")).isEmpty());
    }

    private void addRound(int id, int season, String status, String openAt, String closeAt) {
        jdbc.update("INSERT INTO races VALUES (?, ?, ?, ?, 'SCHEDULED')", id, season, id, "Grand Prix " + id);
        jdbc.update("""
                INSERT INTO market_rounds VALUES (?, ?, ?, ?, ?, ?, '2026-11-24 00:00:00', NULL, 'rule-1', 1)
                """, id, id, season, status, openAt, closeAt);
    }
}
