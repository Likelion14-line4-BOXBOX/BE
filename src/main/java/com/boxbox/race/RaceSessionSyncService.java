package com.boxbox.race;

import com.boxbox.openf1.OpenF1Client;
import com.boxbox.openf1.OpenF1Client.Session;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RaceSessionSyncService {
    private static final Map<String, SessionSlot> SLOTS = Map.of(
            "Practice 1", new SessionSlot("PRACTICE", 1),
            "Practice 2", new SessionSlot("PRACTICE", 2),
            "Practice 3", new SessionSlot("PRACTICE", 3),
            "Qualifying", new SessionSlot("QUALIFYING", 1),
            "Sprint Qualifying", new SessionSlot("SPRINT_QUALIFYING", 1),
            "Sprint Shootout", new SessionSlot("SPRINT_QUALIFYING", 1),
            "Sprint", new SessionSlot("SPRINT", 1),
            "Race", new SessionSlot("RACE", 1));

    private final OpenF1Client openF1;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    public void sync(int season) {
        var sessions = openF1.getSessions(season);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            for (Session session : sessions) {
                if (session.sessionKey() == null || session.meetingKey() == null
                        || session.sessionName() == null || session.dateStart() == null) {
                    continue;
                }
                SessionSlot slot = SLOTS.get(session.sessionName());
                if (slot != null) {
                    save(season, session, slot);
                }
            }
        });
    }

    private void save(int season, Session session, SessionSlot slot) {
        var start = session.dateStart().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime end = session.dateEnd() == null ? null
                : session.dateEnd().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String status = Boolean.TRUE.equals(session.cancelled()) ? "CANCELLED" : "SCHEDULED";

        // The natural key links existing sessions to chat timelines without replacing their IDs.
        jdbc.sql("""
                INSERT INTO race_sessions (race_id, type, session_number, name, starts_at, ends_at, status)
                SELECT id, :type, :number, :name, :start, :end, :status
                FROM races
                WHERE season_year = :season AND external_key = :meetingKey AND status <> 'CANCELLED'
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(race_sessions.status = 'SCHEDULED' AND race_sessions.actual_starts_at IS NULL
                                    AND race_sessions.actual_ends_at IS NULL
                                    AND (race_sessions.starts_at <> :start OR NOT (race_sessions.ends_at <=> :end)
                                         OR race_sessions.status <> :status),
                                    UTC_TIMESTAMP(6), race_sessions.updated_at),
                    starts_at = IF(race_sessions.status = 'SCHEDULED' AND race_sessions.actual_starts_at IS NULL
                                   AND race_sessions.actual_ends_at IS NULL, :start, race_sessions.starts_at),
                    ends_at = IF(race_sessions.status = 'SCHEDULED' AND race_sessions.actual_starts_at IS NULL
                                 AND race_sessions.actual_ends_at IS NULL, :end, race_sessions.ends_at),
                    status = IF(race_sessions.status = 'SCHEDULED' AND race_sessions.actual_starts_at IS NULL
                                AND race_sessions.actual_ends_at IS NULL, :status, race_sessions.status)
                """)
                .param("season", season)
                .param("meetingKey", session.meetingKey().toString())
                .param("type", slot.type())
                .param("number", slot.number())
                .param("name", session.sessionName())
                .param("start", start)
                .param("end", end, Types.TIMESTAMP)
                .param("status", status)
                .update();
    }

    private record SessionSlot(String type, int number) {}
}
