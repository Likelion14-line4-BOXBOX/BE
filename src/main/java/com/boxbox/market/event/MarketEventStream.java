package com.boxbox.market.event;

import com.boxbox.common.api.ApiException;
import com.boxbox.market.round.MarketRoundService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
class MarketEventStream {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final Semaphore connections = new Semaphore(256);

    SseEmitter subscribe(BigInteger round, String cursor, BooleanSupplier valid) {
        var id = new BigDecimal(round);
        var latest = latest(id);
        if (latest == null) {
            throw MarketRoundService.roundNotFound();
        }
        BigInteger start = cursor == null ? latest : replayFrom(round, cursor);
        if (!connections.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_UNAVAILABLE", "마켓 구독 연결이 많습니다.");
        }
        var emitter = new SseEmitter(300_000L);
        var stopped = new AtomicBoolean();
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onError(error -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        Thread.startVirtualThread(() -> {
            try {
                stream(emitter, id, start, valid, stopped);
                emitter.complete();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (IOException | RuntimeException error) {
                if (!stopped.get()) {
                    emitter.completeWithError(error);
                }
            } finally {
                connections.release();
            }
        });
        return emitter;
    }

    private void stream(SseEmitter emitter, BigDecimal round, BigInteger after,
                        BooleanSupplier valid, AtomicBoolean stopped) throws IOException, InterruptedException {
        if (after == null) {
            reset(emitter, round);
            return;
        }
        emitter.send(SseEmitter.event().comment("connected"));
        Instant heartbeat = clock.instant().plusSeconds(15);
        while (!stopped.get() && valid.getAsBoolean()) {
            var events = jdbc.query("""
                    SELECT stream_sequence, event_type, payload, occurred_at FROM stream_events
                    WHERE round_id = ? AND stream_sequence > ? AND published_at IS NOT NULL AND expires_at > ?
                    ORDER BY stream_sequence LIMIT 100
                    """, (row, index) -> new Event(row.getBigDecimal("stream_sequence").toBigIntegerExact(),
                    row.getString("event_type"), row.getString("payload"),
                    row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                    round, new BigDecimal(after), utc(clock.instant()));
            for (var event : events) {
                if (!event.sequence().equals(after.add(BigInteger.ONE))) {
                    reset(emitter, round);
                    return;
                }
                String eventId = round.toPlainString() + ":" + event.sequence();
                emitter.send(SseEmitter.event().id(eventId).name(event.type())
                        .data(envelope(eventId, event.at(), json.readTree(event.payload()))));
                after = event.sequence();
            }
            if (!clock.instant().isBefore(heartbeat)) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                heartbeat = clock.instant().plusSeconds(15);
            }
            if (events.size() < 100) {
                Thread.sleep(500);
            }
        }
    }

    private void reset(SseEmitter emitter, BigDecimal round) throws IOException {
        // Clear the browser's last event ID before it reconnects.
        var latest = latest(round);
        String eventId = round.toPlainString() + ":" + (latest == null ? BigInteger.ZERO : latest);
        emitter.send(SseEmitter.event().id("").name("RESET_REQUIRED")
                .data(envelope(eventId, clock.instant(), Map.of("roundId", round.toPlainString()))));
    }

    private static Map<String, ?> envelope(String id, Instant at, Object payload) {
        return Map.of("eventId", id, "occurredAt", at.toString(), "payload", payload);
    }

    private BigInteger replayFrom(BigInteger round, String cursor) {
        String prefix = round + ":";
        if (!cursor.startsWith(prefix) || !cursor.substring(prefix.length()).matches("[1-9][0-9]{0,19}")) {
            return null;
        }
        var sequence = new BigDecimal(cursor.substring(prefix.length()));
        return jdbc.query("""
                SELECT stream_sequence FROM stream_events
                WHERE round_id = ? AND stream_sequence = ? AND published_at IS NOT NULL AND expires_at > ?
                """, (row, index) -> row.getBigDecimal(1).toBigIntegerExact(),
                new BigDecimal(round), sequence, utc(clock.instant())).stream().findFirst().orElse(null);
    }

    private BigInteger latest(BigDecimal round) {
        return jdbc.query("SELECT stream_sequence FROM market_rounds WHERE id = ?",
                (row, index) -> row.getBigDecimal(1).toBigIntegerExact(), round).stream().findFirst().orElse(null);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Event(BigInteger sequence, String type, String payload, Instant at) {}
}
