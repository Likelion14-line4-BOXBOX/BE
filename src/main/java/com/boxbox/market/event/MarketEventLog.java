package com.boxbox.market.event;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class MarketEventLog {
    private static final SecureRandom IDS = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MarketEventLog() {}

    // Callers hold the round lock and write the event in their state-change transaction.
    public static void append(JdbcTemplate jdbc, BigDecimal roundId, String type,
                              Map<String, ?> payload, LocalDateTime when) {
        jdbc.update("UPDATE market_rounds SET stream_sequence = stream_sequence + 1 WHERE id = ?", roundId);
        var sequence = jdbc.queryForObject("SELECT stream_sequence FROM market_rounds WHERE id = ?",
                BigDecimal.class, roundId);
        jdbc.update("""
                INSERT INTO stream_events (id, round_id, stream_sequence, event_type, payload,
                    occurred_at, published_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, IDS.nextLong(1, Long.MAX_VALUE), roundId, sequence, type, JSON.writeValueAsString(payload),
                when, when, when.plusMinutes(10));
    }
}
