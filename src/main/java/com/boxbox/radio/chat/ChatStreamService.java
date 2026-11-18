package com.boxbox.radio.chat;

import com.boxbox.race.SessionNames;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.chat.dto.ChatResponses;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 커밋된 DB 이벤트가 전달 원본이다. 네트워크 전송 중에는 트랜잭션/방 잠금을 유지하지 않는다. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class ChatStreamService {
    private final JdbcTemplate jdbc;
    private final ChatRepository reads;
    private final ChatWriteRepository writes;
    private final ChatTimelineReader timeline;
    private final JsonMapper json;
    private final Duration retention;

    ChatStreamService(JdbcTemplate jdbc, ChatRepository reads, ChatWriteRepository writes, ChatTimelineReader timeline,
                      JsonMapper json, @Value("${boxbox.chat.event-retention:PT10M}") Duration retention) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.writes = writes;
        this.timeline = timeline;
        this.json = json;
        this.retention = retention;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Open open(long roomId, String lastEventId, String loginId) {
        var viewer = viewer(loginId);
        var room = reads.room(roomId);
        if (state(room, Instant.now()) == ChatRoomState.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_ROOM_CLOSED", "종료된 채팅방은 이력으로 조회해 주세요.");
        }
        return new Open(ChatEventCursor.parse(lastEventId, roomId, room.sequence()), viewer == null ? null : "user:" + viewer.id());
    }

    /** LIVE 연결이 준비된 뒤에만 호출한다. 목록/이력 조회나 메시지 전송은 이 경로를 호출하지 않는다. */
    boolean participate(long roomId, String loginId) {
        return participate(roomId, loginId, () -> true);
    }

    boolean participate(long roomId, String loginId, BooleanSupplier credentialsValid) {
        if (loginId == null) return false;
        if (loginId.isBlank()) throw ApiException.unauthorized();
        long userId = jdbc.query("""
                SELECT id FROM users WHERE login_id=? AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED') FOR UPDATE
                """, (rs, row) -> rs.getLong("id"), loginId).stream().findFirst().orElseThrow(ApiException::unauthorized);
        var room = writes.lockRoom(roomId);
        if (!credentialsValid.getAsBoolean()) throw ApiException.unauthorized();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // 연결 준비/잠금 대기 사이에 종료됐다면 뒤늦게 지급하지 않는다.
        if (ChatRoomState.at(room.status(), room.opensAt(), room.closesAt(), now) != ChatRoomState.LIVE) return false;
        writes.recordJoin(roomId, userId, now);
        return true;
    }

    Batch poll(long roomId, long after, String loginId) {
        var viewer = viewer(loginId); // 연결 중 탈퇴/정지 상태 변경도 반영한다.
        writes.lockRoom(roomId);
        var room = reads.room(roomId);
        Instant now = Instant.now(); // 방 잠금 대기 이후의 시각이다.
        synchronize(room, now);
        room = reads.room(roomId);
        ChatRoomState status = state(room, now);
        long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stream_events WHERE room_id=? AND stream_sequence>? AND stream_sequence<=? AND expires_at>?
                """, Long.class, roomId, after, room.sequence(), ChatRepository.utc(now));
        // 중간 이벤트가 정리되거나 만료된 경우 최신 위치로 조용히 건너뛰지 않는다.
        if (after > room.sequence() || count != room.sequence() - after) return new Batch(List.of(), room.sequence(), status, true);
        var records = jdbc.query("""
                SELECT id,stream_sequence,event_type,payload,occurred_at FROM stream_events
                WHERE room_id=? AND stream_sequence>? AND stream_sequence<=? ORDER BY stream_sequence LIMIT 100
                """, (rs, row) -> new StoredEvent(rs.getLong("id"), rs.getLong("stream_sequence"), rs.getString("event_type"),
                rs.getString("payload"), ChatRepository.instant(rs, "occurred_at")), roomId, after, room.sequence());
        if (records.size() != Math.min(100L, room.sequence() - after)) return new Batch(List.of(), room.sequence(), status, true);
        long expected = after;
        for (var record : records) {
            if (record.sequence() != ++expected) return new Batch(List.of(), room.sequence(), status, true);
        }
        var events = new ArrayList<Event>();
        for (var record : records) events.add(render(room, viewer, record));
        return new Batch(List.copyOf(events), room.sequence(), status, false);
    }

    private ChatRepository.Viewer viewer(String loginId) {
        if (loginId == null) return null;
        if (loginId.isBlank()) throw ApiException.unauthorized();
        return reads.viewer(loginId).orElseThrow(ApiException::unauthorized);
    }

    private void synchronize(ChatRepository.Room room, Instant now) {
        long sequence = room.sequence();
        ChatRoomState desired = state(room, now);
        var lastStatus = jdbc.query("SELECT payload FROM stream_events WHERE room_id=? AND event_type='ROOM_STATUS_CHANGED' ORDER BY stream_sequence DESC LIMIT 1",
                (rs, row) -> parse(rs.getString("payload")).get("status"), room.id());
        boolean unannouncedArchive = desired == ChatRoomState.ARCHIVED && (lastStatus.isEmpty() || !"ARCHIVED".equals(lastStatus.getFirst()));
        // 외부 운영 작업이 status를 먼저 ARCHIVED로 바꿔도 연결에는 종료 이벤트를 남긴다.
        if (!room.storedStatus().equals(desired.name()) || unannouncedArchive) {
            jdbc.update("UPDATE chat_rooms SET status=?,archived_at=CASE WHEN ?='ARCHIVED' THEN COALESCE(archived_at,?) ELSE archived_at END WHERE id=?",
                    desired.name(), desired.name(), ChatRepository.utc(now), room.id());
            sequence = append(room, sequence, "ROOM_STATUS_CHANGED", new RoomStatus(Long.toString(room.id()), desired.name(), room.opensAt(), room.closesAt()), now, now);
        }
        // 실제 시각만 이벤트로 변환한다. 과거 SESSION_CHANGED는 이력 구분선이므로 삭제하지 않는다.
        Set<String> recorded = new HashSet<>();
        jdbc.query("SELECT payload,occurred_at FROM stream_events WHERE room_id=? AND event_type='SESSION_CHANGED'", rs -> {
            var payload = parse(rs.getString("payload"));
            if (payload.get("markerId") instanceof String id) recorded.add(id);
            if (payload.get("sessionId") != null) recorded.add(payload.get("sessionId") + "@" + ChatRepository.instant(rs, "occurred_at"));
        }, room.id());
        var actual = jdbc.query("SELECT id,name,type,session_number,actual_starts_at,actual_ends_at FROM race_sessions WHERE race_id=? ORDER BY id", (rs, row) ->
                new ActualSession(rs.getString("id"), SessionNames.display(rs.getString("type"),rs.getInt("session_number"),rs.getString("name")), ChatRepository.instant(rs, "actual_starts_at"),
                        ChatRepository.instant(rs, "actual_ends_at")), room.raceId());
        var markers = new ArrayList<ChatResponses.Marker>();
        for (var session : actual) {
            if (session.start() != null && !session.start().isAfter(now)) markers.add(new ChatResponses.Marker("session_" + session.id() + "_start", session.id(), session.name(), session.start()));
            if (session.end() != null && !session.end().isAfter(now)) markers.add(new ChatResponses.Marker("session_" + session.id() + "_end", session.id(), session.name() + " 종료", session.end()));
        }
        markers.sort(java.util.Comparator.comparing(ChatResponses.Marker::occurredAt).thenComparing(ChatResponses.Marker::markerId));
        for (var marker : markers) {
            if (recorded.contains(marker.markerId()) || recorded.contains(marker.sessionId() + "@" + marker.occurredAt())) continue;
            sequence = append(room, sequence, "SESSION_CHANGED", marker, marker.occurredAt(), now);
        }
    }

    private long append(ChatRepository.Room room, long sequence, String type, Object payload, Instant occurredAt, Instant now) {
        long next = Math.addExact(sequence, 1);
        jdbc.update("UPDATE chat_rooms SET stream_sequence=?,updated_at=? WHERE id=?", next, ChatRepository.utc(now), room.id());
        jdbc.update("INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at) VALUES (?,?,?,?,?,?)",
                room.id(), next, type, json.writeValueAsString(payload), ChatRepository.utc(occurredAt), ChatRepository.utc(now.plus(retention)));
        return next;
    }

    private Event render(ChatRepository.Room room, ChatRepository.Viewer viewer, StoredEvent event) {
        var payload = parse(event.payload());
        Object data;
        switch (event.type()) {
            case "MESSAGE_CREATED", "MESSAGE_DELETED" -> {
                long messageId = id(payload.get("messageId"));
                if (payload.containsKey("roomId") && id(payload.get("roomId")) != room.id()) throw unavailable();
                data = jdbc.query("""
                        SELECT m.*,u.nickname,u.avatar_asset_id,u.status AS sender_status,u.deleted_at AS sender_deleted
                        FROM chat_messages m JOIN users u ON u.id=m.sender_id WHERE m.room_id=? AND m.id=?
                        """, (rs, row) -> {
                    boolean deleted = rs.getObject("deleted_at") != null;
                    boolean withdrawn = rs.getObject("sender_deleted") != null || "WITHDRAWN".equals(rs.getString("sender_status"));
                    var author = deleted ? null : new Author(rs.getString("sender_id"), withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"),
                            withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
                    if (payload.containsKey("clientMessageId") && !rs.getString("client_message_id").equals(payload.get("clientMessageId"))) throw unavailable();
                    return new ChatResponses.Message(rs.getString("id"), rs.getString("client_message_id"), rs.getString("room_id"), author,
                            deleted ? null : rs.getString("content"), ChatRepository.instant(rs, "created_at"), deleted,
                            !deleted && viewer != null && viewer.active() && viewer.id() == rs.getLong("sender_id"));
                }, room.id(), messageId).stream().findFirst().orElseThrow(ChatStreamService::unavailable);
                if (event.type().equals("MESSAGE_DELETED") && !((ChatResponses.Message) data).isDeleted()) throw unavailable();
            }
            case "SESSION_CHANGED" -> {
                String markerId = payload.get("markerId") instanceof String key ? key : "event_" + event.id();
                String sessionId = payload.get("sessionId") == null ? null : payload.get("sessionId").toString();
                data = timeline.markers(room.id(), room.raceId(), event.sequence(), event.at(), event.at(), true).stream()
                        .filter(marker -> sessionId == null ? marker.markerId().equals(markerId) : sessionId.equals(marker.sessionId()))
                        .findFirst().orElseThrow(ChatStreamService::unavailable);
            }
            case "ROOM_STATUS_CHANGED" -> {
                if (id(payload.get("roomId")) != room.id()) throw unavailable();
                try { ChatRoomState.valueOf((String) payload.get("status")); }
                catch (RuntimeException error) { throw unavailable(); }
                data = payload;
            }
            default -> throw unavailable();
        }
        return new Event(event.sequence(), event.type(), new Envelope(cursor(room.id(), event.sequence()), event.at(), data));
    }

    private static Map<String, Object> parse(String payload) {
        try { return JsonParserFactory.getJsonParser().parseMap(payload); }
        catch (RuntimeException error) { throw unavailable(); }
    }
    private static long id(Object value) {
        try { long id = Long.parseLong(String.valueOf(value)); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw unavailable();
    }
    static String cursor(long room, long sequence) { return new ChatEventCursor(room, sequence).toString(); }
    private static ChatRoomState state(ChatRepository.Room room, Instant now) { return ChatRoomState.at(room.storedStatus(), room.opensAt(), room.closesAt(), now); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_STREAM_UNAVAILABLE", "채팅 이벤트 기록을 확인해 주세요."); }
    record Open(long sequence, String identity) { }
    record Envelope(String eventId, Instant occurredAt, Object payload) { }
    record Event(long sequence, String type, Envelope data) { }
    record Batch(List<Event> events, long latest, ChatRoomState status, boolean resetRequired) { }
    record RoomStatus(String roomId, String status, Instant opensAt, Instant closesAt) { }
    private record StoredEvent(long id, long sequence, String type, String payload, Instant at) { }
    private record ActualSession(String id, String name, Instant start, Instant end) { }
}
