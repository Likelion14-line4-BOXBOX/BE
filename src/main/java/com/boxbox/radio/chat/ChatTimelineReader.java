package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.chat.dto.ChatResponses.Marker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ChatTimelineReader {
    private final JdbcTemplate jdbc;

    List<Marker> markers(long roomId, long raceId, long sequence, Instant lower, Instant upper, boolean inclusiveUpper) {
        var markers = new LinkedHashMap<String, Marker>();
        var sessions = jdbc.query("SELECT id,name,actual_starts_at,actual_ends_at FROM race_sessions WHERE race_id=?",
                (rs, row) -> new Session(rs.getString("id"), rs.getString("name"), ChatRepository.instant(rs, "actual_starts_at"),
                        ChatRepository.instant(rs, "actual_ends_at")), raceId);
        for (var session : sessions) {
            if (within(session.start(), lower, upper, inclusiveUpper)) put(markers, new Marker("session_" + session.id() + "_start", session.id(), session.name(), session.start()));
            if (within(session.end(), lower, upper, inclusiveUpper)) put(markers, new Marker("session_" + session.id() + "_end", session.id(), session.name() + " 종료", session.end()));
        }
        String range = " AND occurred_at" + (inclusiveUpper ? "<=?" : "<?") + (lower == null ? "" : " AND occurred_at>=? ");
        var args = new ArrayList<Object>(List.of(roomId, sequence, ChatRepository.utc(upper)));
        if (lower != null) args.add(ChatRepository.utc(lower));
        var events = jdbc.query("SELECT id,payload,occurred_at FROM stream_events WHERE room_id=? AND stream_sequence<=? "
                + " AND event_type='SESSION_CHANGED' " + range + " ORDER BY stream_sequence", (rs, row) ->
                new Event(rs.getLong("id"), rs.getString("payload"), ChatRepository.instant(rs, "occurred_at")), args.toArray());
        for (var event : events) {
            try {
                var payload = JsonParserFactory.getJsonParser().parseMap(event.payload());
                String label = (String) payload.get("label");
                String sessionId = payload.get("sessionId") == null ? null : payload.get("sessionId").toString();
                Object key = payload.get("markerId");
                String markerId = key == null ? "event_" + event.id() : (String) key;
                if (label == null || label.isBlank() || markerId.isBlank()
                        || (sessionId != null && sessions.stream().noneMatch(session -> session.id().equals(sessionId)))) throw new IllegalArgumentException();
                for (var session : sessions) {
                    if (!session.id().equals(sessionId)) continue;
                    if (event.at().equals(session.start())) markerId = "session_" + sessionId + "_start";
                    else if (event.at().equals(session.end())) markerId = "session_" + sessionId + "_end";
                }
                // 같은 실제 세션 전환의 이벤트가 있으면 그 기록을 우선해 구분선을 중복 표시하지 않는다.
                if (sessionId != null) {
                    markers.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId())
                            && event.at().equals(entry.getValue().occurredAt()));
                }
                put(markers, new Marker(markerId, sessionId, label, event.at()));
            } catch (RuntimeException error) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_HISTORY_UNAVAILABLE", "기록된 채팅 구분선 데이터를 확인해 주세요.");
            }
        }
        return markers.values().stream().sorted(Comparator.comparing(Marker::occurredAt).thenComparing(Marker::markerId)).toList();
    }

    private static void put(LinkedHashMap<String, Marker> markers, Marker marker) { markers.put(marker.markerId(), marker); }
    private static boolean within(Instant at, Instant lower, Instant upper, boolean inclusiveUpper) {
        return at != null && (lower == null || !at.isBefore(lower)) && (at.isBefore(upper) || inclusiveUpper && at.equals(upper));
    }
    private record Session(String id, String name, Instant start, Instant end) { }
    private record Event(long id, String payload, Instant at) { }
}
