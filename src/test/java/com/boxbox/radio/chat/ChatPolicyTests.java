package com.boxbox.radio.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChatPolicyTests {
    @ParameterizedTest
    @CsvSource({"SCHEDULED,-1,SCHEDULED", "SCHEDULED,0,LIVE", "SCHEDULED,9,LIVE", "LIVE,10,ARCHIVED",
            "LIVE,11,ARCHIVED", "LIVE,-1,SCHEDULED", "ARCHIVED,-1,ARCHIVED", "ARCHIVED,0,ARCHIVED"})
    void statusChangesExactlyAtDatabaseOperatingBoundaries(String stored, int seconds, String expected) {
        Instant opens = Instant.parse("2026-11-14T00:00:00Z");
        assertThat(ChatRoomState.at(stored, opens, opens.plusSeconds(10), opens.plusSeconds(seconds)).name()).isEqualTo(expected);
    }

    @Test
    void concurrentConnectionsDeduplicateAndStaleCloseDoesNotRemoveNewConnection() throws Exception {
        var registry = new ChatPresenceRegistry();
        var connections = new ArrayList<ChatPresenceRegistry.Connection>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<ChatPresenceRegistry.Connection>>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> registry.connect(1, "user:1")));
            for (var future : futures) connections.add(future.get(5, TimeUnit.SECONDS));
        }
        assertThat(registry.counts(List.of(1L))).containsEntry(1L, 1L);
        connections.forEach(ChatPresenceRegistry.Connection::close);
        var next = registry.connect(1, "user:1");
        connections.forEach(ChatPresenceRegistry.Connection::close);
        assertThat(registry.counts(List.of(1L))).containsEntry(1L, 1L);
        next.close();
        assertThat(registry.counts(List.of(1L))).containsEntry(1L, 0L);
    }
}
