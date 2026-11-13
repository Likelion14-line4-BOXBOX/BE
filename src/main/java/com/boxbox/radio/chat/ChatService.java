package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.chat.dto.ChatResponses;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ChatService {
    private final ChatRepository repository;
    private final ChatTimelineReader timeline;
    private final ChatPresenceRegistry presence;
    private final int activeSeason;

    ChatService(ChatRepository repository, ChatTimelineReader timeline, ChatPresenceRegistry presence,
                @Value("${boxbox.chat.active-season:2026}") int activeSeason) {
        if (activeSeason < 1000 || activeSeason > 9999) throw new IllegalArgumentException("활성 시즌은 네 자리 연도여야 합니다.");
        this.repository = repository;
        this.timeline = timeline;
        this.presence = presence;
        this.activeSeason = activeSeason;
    }

    public ChatResponses.RoomPage rooms(Map<String, String> params, String loginId) {
        viewer(loginId);
        var request = ChatPageRequest.rooms(params, activeSeason);
        Instant now = now();
        var cursor = request.cursor() == null ? null : ChatCursor.Rooms.decode(request.cursor(), request, now);
        Instant anchor = cursor == null ? now : cursor.anchor();
        long maxId = cursor == null ? repository.maxRoomId() : cursor.maxId();
        var fetched = repository.rooms(request, anchor, maxId, cursor);
        boolean hasNext = fetched.size() > request.size();
        var rows = List.copyOf(fetched.subList(0, Math.min(fetched.size(), request.size())));
        var counts = presence.counts(rows.stream().map(ChatRepository.Room::id).toList());
        String next = null;
        if (hasNext) {
            var last = rows.getLast();
            next = new ChatCursor.Rooms(anchor, maxId, last.closesAt(), last.id()).encode(request);
        }
        return new ChatResponses.RoomPage(rows.stream().map(room -> summary(room, anchor, counts.get(room.id()))).toList(), next, hasNext);
    }

    public ChatResponses.RoomDetail detail(long roomId, Map<String, String> params, String loginId) {
        var viewer = viewer(loginId);
        if (!params.isEmpty()) throw ApiException.invalidRequest("채팅방 상세에는 조회 조건을 지정하지 않습니다.");
        var room = repository.room(roomId);
        var response = summary(room, now(), presence.counts(List.of(roomId)).get(roomId));
        return ChatResponses.RoomDetail.from(response, viewer != null && viewer.active() && response.status().equals("LIVE"));
    }

    public ChatResponses.History history(long roomId, Map<String, String> params, String loginId) {
        var viewer = viewer(loginId);
        var request = ChatPageRequest.messages(params);
        var room = repository.room(roomId);
        Instant now = now();
        var cursor = request.cursor() == null ? null : ChatCursor.Messages.decode(request.cursor(), roomId, request.size(), room.sequence(), now);
        Instant anchor = cursor == null ? now : cursor.anchor();
        long maxId = cursor == null ? repository.maxMessageId(roomId) : cursor.maxId();
        long sequence = cursor == null ? room.sequence() : cursor.eventSequence();
        var fetched = repository.messages(roomId, request.size(), anchor, maxId, cursor, viewer);
        boolean hasNext = fetched.size() > request.size();
        var rows = List.copyOf(fetched.subList(0, Math.min(fetched.size(), request.size())));
        Instant lower = hasNext ? rows.getLast().createdAt() : null;
        Instant upper = cursor == null ? anchor : cursor.createdAt();
        var markers = timeline.markers(roomId, room.raceId(), sequence, lower, upper, cursor == null);
        String next = null;
        if (hasNext) {
            var last = rows.getLast();
            next = new ChatCursor.Messages(anchor, maxId, sequence, last.createdAt(), Long.parseLong(last.messageId())).encode(roomId, request.size());
        }
        return ChatResponses.History.from(new ChatResponses.MessagePage(rows, next, hasNext, markers), roomId, sequence);
    }

    private ChatRepository.Viewer viewer(String loginId) {
        if (loginId == null) return null;
        if (loginId.isBlank()) throw ApiException.unauthorized();
        return repository.viewer(loginId).orElseThrow(ApiException::unauthorized);
    }

    private static ChatResponses.Room summary(ChatRepository.Room room, Instant now, long online) {
        return new ChatResponses.Room(Long.toString(room.id()), Long.toString(room.raceId()), room.raceName(), room.roundNumber(),
                ChatRoomState.at(room.storedStatus(), room.opensAt(), room.closesAt(), now).name(), online, room.opensAt(), room.closesAt(),
                room.participantCount(), room.weekendStartsAt(), room.weekendEndsAt());
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
