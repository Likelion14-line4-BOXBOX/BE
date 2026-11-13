package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.chat.dto.ChatResponses.Message;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class ChatMessageService {
    private final ChatWriteRepository repository;
    private final JsonMapper json;
    private final Duration eventRetention;

    ChatMessageService(ChatWriteRepository repository, JsonMapper json,
                       @Value("${boxbox.chat.event-retention:PT10M}") Duration eventRetention) {
        if (eventRetention.isNegative() || eventRetention.isZero()) throw new IllegalArgumentException("chat event retention must be positive");
        this.repository = repository;
        this.json = json;
        this.eventRetention = eventRetention;
    }

    Message send(long roomId, Map<String, Object> body, String loginId) {
        var actor = repository.lockActor(loginId);
        var input = ChatMessageInput.parse(body);
        var room = repository.lockRoom(roomId);
        var existing = repository.byClient(roomId, actor.id(), input.clientMessageId());
        if (existing.isPresent()) {
            var message = existing.get();
            if (message.deleted() || !MessageDigest.isEqual(input.hash(), message.hash())) {
                throw new ApiException(HttpStatus.CONFLICT, "CLIENT_MESSAGE_CONFLICT", "이미 사용한 clientMessageId입니다.");
            }
            return response(roomId, actor, message);
        }
        // 대기 중 운영 시간이 끝날 수 있으므로 방 잠금을 얻은 후 시각을 확인한다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (ChatRoomState.at(room.status(), room.opensAt(), room.closesAt(), now) != ChatRoomState.LIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_ROOM_CLOSED", "현재 메시지를 전송할 수 없는 채팅방입니다.");
        }
        repository.recordFirstMessage(roomId, actor.id(), now);
        var message = repository.insert(roomId, actor.id(), input, now);
        append(room, message, "MESSAGE_CREATED", now);
        return response(roomId, actor, message);
    }

    void delete(long roomId, long messageId, String loginId) {
        var actor = repository.lockActor(loginId);
        var room = repository.lockRoom(roomId);
        var message = repository.lockMessage(roomId, messageId);
        if (message.senderId() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_MESSAGE_AUTHOR", "본인 메시지만 삭제할 수 있습니다.");
        }
        if (message.deleted()) return;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.delete(message.id(), now);
        append(room, message, "MESSAGE_DELETED", now);
    }

    private void append(ChatWriteRepository.LockedRoom room, ChatWriteRepository.StoredMessage message, String type, Instant now) {
        // 삭제 원문과 작성자 전용 canDelete를 보관하지 않는다. SSE는 수신 시 현재 메시지를 조합한다.
        String payload = json.writeValueAsString(new EventReference(Long.toString(message.id()), Long.toString(room.id()), message.clientMessageId()));
        repository.appendEvent(room, type, payload, now, now.plus(eventRetention));
    }

    private static Message response(long roomId, ChatWriteRepository.Actor actor, ChatWriteRepository.StoredMessage message) {
        return new Message(Long.toString(message.id()), message.clientMessageId(), Long.toString(roomId),
                new Author(Long.toString(actor.id()), actor.nickname(), actor.avatarAssetId()), message.content(), message.createdAt(), false, true);
    }

    private record EventReference(String messageId, String roomId, String clientMessageId) { }
}
