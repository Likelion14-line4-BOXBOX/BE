package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.chat.dto.ChatResponses.Message;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ChatRepository {
    private final JdbcTemplate jdbc;
    private static final String ROOMS = """
            SELECT c.*,r.name AS race_name,r.round_number,r.weekend_starts_at,r.weekend_ends_at,
                (SELECT COUNT(*) FROM chat_participants p WHERE p.room_id=c.id) AS participant_count
            FROM chat_rooms c JOIN races r ON r.id=c.race_id
            """;

    Optional<Viewer> viewer(String loginId) {
        return jdbc.query("SELECT id,status FROM users WHERE login_id=? AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED')",
                (rs, row) -> new Viewer(rs.getLong("id"), "ACTIVE".equals(rs.getString("status"))), loginId).stream().findFirst();
    }

    Room room(long roomId) {
        return jdbc.query(ROOMS + " WHERE c.id=?", (rs, row) -> room(rs), roomId).stream().findFirst().orElseThrow(ChatRepository::notFound);
    }

    long maxRoomId() { return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM chat_rooms", Long.class); }
    long maxMessageId(long roomId) { return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM chat_messages WHERE room_id=?", Long.class, roomId); }

    List<Room> rooms(ChatPageRequest request, Instant anchor, long maxId, ChatCursor.Rooms cursor) {
        var params = new MapSqlParameterSource().addValue("season", request.season()).addValue("anchor", utc(anchor))
                .addValue("maxId", maxId).addValue("limit", request.size() + 1);
        String filter = "";
        if (request.status() != null) {
            filter = " AND (CASE WHEN c.status='ARCHIVED' OR c.closes_at<=:anchor THEN 'ARCHIVED' "
                    + " WHEN c.opens_at>:anchor THEN 'SCHEDULED' ELSE 'LIVE' END)=:status ";
            params.addValue("status", request.status().name());
        }
        if (cursor != null) {
            filter += " AND (c.closes_at<:lastTime OR (c.closes_at=:lastTime AND c.id<:lastId)) ";
            params.addValue("lastTime", utc(cursor.closesAt())).addValue("lastId", cursor.id());
        }
        return named().query(ROOMS + " WHERE r.season_year=:season AND c.id<=:maxId " + filter
                + " ORDER BY c.closes_at DESC,c.id DESC LIMIT :limit", params, (rs, row) -> room(rs));
    }

    List<Message> messages(long roomId, int size, Instant anchor, long maxId, ChatCursor.Messages cursor, Viewer viewer) {
        var params = new MapSqlParameterSource().addValue("room", roomId).addValue("anchor", utc(anchor))
                .addValue("maxId", maxId).addValue("limit", size + 1);
        String after = "";
        if (cursor != null) {
            after = " AND (m.created_at<:lastTime OR (m.created_at=:lastTime AND m.id<:lastId)) ";
            params.addValue("lastTime", utc(cursor.createdAt())).addValue("lastId", cursor.id());
        }
        return named().query("""
                SELECT m.*,u.nickname,u.avatar_asset_id,u.status AS sender_status,u.deleted_at AS sender_deleted
                FROM chat_messages m JOIN users u ON u.id=m.sender_id
                WHERE m.room_id=:room AND m.id<=:maxId AND m.created_at<=:anchor
                """ + after + " ORDER BY m.created_at DESC,m.id DESC LIMIT :limit", params, (rs, row) -> {
                    boolean deleted = rs.getObject("deleted_at") != null;
                    boolean withdrawn = rs.getObject("sender_deleted") != null || "WITHDRAWN".equals(rs.getString("sender_status"));
                    Author author = deleted ? null : new Author(rs.getString("sender_id"), withdrawn ? "탈퇴한 사용자" : rs.getString("nickname"),
                            withdrawn ? "avatar_default" : rs.getString("avatar_asset_id"));
                    boolean canDelete = !deleted && viewer != null && viewer.active() && viewer.id() == rs.getLong("sender_id");
                    return new Message(rs.getString("id"), rs.getString("client_message_id"), rs.getString("room_id"), author,
                            deleted ? null : rs.getString("content"), instant(rs, "created_at"), deleted, canDelete);
                });
    }

    private static Room room(ResultSet rs) throws SQLException {
        return new Room(rs.getLong("id"), rs.getLong("race_id"), rs.getString("race_name"), rs.getInt("round_number"),
                rs.getString("status"), instant(rs, "opens_at"), instant(rs, "closes_at"), instant(rs, "weekend_starts_at"),
                instant(rs, "weekend_ends_at"), rs.getLong("participant_count"), rs.getLong("stream_sequence"));
    }

    static Instant instant(ResultSet rs, String field) throws SQLException {
        LocalDateTime value = rs.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다."); }
    private NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(jdbc); }
    record Viewer(long id, boolean active) { }
    record Room(long id, long raceId, String raceName, int roundNumber, String storedStatus, Instant opensAt, Instant closesAt,
                Instant weekendStartsAt, Instant weekendEndsAt, long participantCount, long sequence) { }
}
