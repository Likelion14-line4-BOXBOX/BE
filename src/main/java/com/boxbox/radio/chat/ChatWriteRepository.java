package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ChatWriteRepository {
    private final JdbcTemplate jdbc;

    Actor lockActor(String loginId) {
        if (loginId == null) throw ApiException.unauthorized();
        Actor actor = jdbc.query("SELECT id,status,nickname,avatar_asset_id FROM users WHERE login_id=? AND deleted_at IS NULL FOR UPDATE",
                (rs, row) -> new Actor(rs.getLong("id"), rs.getString("status"), rs.getString("nickname"), rs.getString("avatar_asset_id")),
                loginId).stream().findFirst().orElseThrow(ApiException::unauthorized);
        if ("SUSPENDED".equals(actor.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHAT_RESTRICTED", "채팅 이용이 제한된 계정입니다.");
        }
        if (!"ACTIVE".equals(actor.status())) throw ApiException.unauthorized();
        return actor;
    }

    LockedRoom lockRoom(long roomId) {
        return jdbc.query("SELECT id,status,opens_at,closes_at,stream_sequence FROM chat_rooms WHERE id=? FOR UPDATE",
                (rs, row) -> new LockedRoom(rs.getLong("id"), rs.getString("status"), ChatRepository.instant(rs, "opens_at"),
                        ChatRepository.instant(rs, "closes_at"), rs.getLong("stream_sequence")), roomId)
                .stream().findFirst().orElseThrow(ChatRepository::notFound);
    }

    Optional<StoredMessage> byClient(long roomId, long senderId, String clientId) {
        return jdbc.query("SELECT * FROM chat_messages WHERE room_id=? AND sender_id=? AND client_message_id=? FOR UPDATE",
                (rs, row) -> message(rs), roomId, senderId, clientId).stream().findFirst();
    }

    StoredMessage lockMessage(long roomId, long messageId) {
        return jdbc.query("SELECT * FROM chat_messages WHERE room_id=? AND id=? FOR UPDATE",
                (rs, row) -> message(rs), roomId, messageId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "메시지를 찾을 수 없습니다."));
    }

    void recordFirstMessage(long roomId, long userId, Instant now) {
        // 방 잠금 아래에서 참가 유일성을 보장한다. 입장/도장 기록은 기존 값을 유지한다.
        boolean exists = !jdbc.queryForList("SELECT user_id FROM chat_participants WHERE room_id=? AND user_id=?", Long.class, roomId, userId).isEmpty();
        if (exists) {
            jdbc.update("UPDATE chat_participants SET first_message_at=COALESCE(first_message_at,?) WHERE room_id=? AND user_id=?",
                    ChatRepository.utc(now), roomId, userId);
        } else {
            jdbc.update("INSERT INTO chat_participants(room_id,user_id,first_joined_at,last_joined_at,first_message_at) VALUES (?,?,?,?,?)",
                    roomId, userId, ChatRepository.utc(now), ChatRepository.utc(now), ChatRepository.utc(now));
        }
    }

    StoredMessage insert(long roomId, long senderId, ChatMessageInput input, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO chat_messages(room_id,sender_id,client_message_id,request_hash,content,created_at)
                    VALUES (?,?,?,?,?,?)
                    """, new String[] {"id"});
            statement.setLong(1, roomId);
            statement.setLong(2, senderId);
            statement.setString(3, input.clientMessageId());
            statement.setBytes(4, input.hash());
            statement.setString(5, input.content());
            statement.setObject(6, ChatRepository.utc(now));
            return statement;
        }, key);
        return new StoredMessage(key.getKey().longValue(), senderId, input.clientMessageId(), input.hash(), input.content(), now, false);
    }

    void delete(long messageId, Instant now) {
        jdbc.update("UPDATE chat_messages SET content=NULL,deleted_at=? WHERE id=?", ChatRepository.utc(now), messageId);
    }

    void appendEvent(LockedRoom room, String type, String payload, Instant now, Instant expiresAt) {
        long sequence = Math.addExact(room.sequence(), 1);
        jdbc.update("UPDATE chat_rooms SET stream_sequence=?,updated_at=? WHERE id=?", sequence, ChatRepository.utc(now), room.id());
        jdbc.update("""
                INSERT INTO stream_events(room_id,stream_sequence,event_type,payload,occurred_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, room.id(), sequence, type, payload, ChatRepository.utc(now), ChatRepository.utc(expiresAt));
    }

    private static StoredMessage message(ResultSet rs) throws SQLException {
        return new StoredMessage(rs.getLong("id"), rs.getLong("sender_id"), rs.getString("client_message_id"),
                rs.getBytes("request_hash"), rs.getString("content"), ChatRepository.instant(rs, "created_at"), rs.getObject("deleted_at") != null);
    }

    record Actor(long id, String status, String nickname, String avatarAssetId) { }
    record LockedRoom(long id, String status, Instant opensAt, Instant closesAt, long sequence) { }
    record StoredMessage(long id, long senderId, String clientMessageId, byte[] hash, String content, Instant createdAt, boolean deleted) { }
}
