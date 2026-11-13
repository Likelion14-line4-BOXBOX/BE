package com.boxbox.radio.chat.dto;

import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.post.dto.PostDetailResponse.Author;
import java.time.Instant;
import java.util.List;

public final class ChatResponses {
    private ChatResponses() { }

    public record Room(String roomId, String raceId, String raceName, int roundNumber, String status,
                       long onlineCount, Instant opensAt, Instant closesAt, long participantCount,
                       Instant weekendStartsAt, Instant weekendEndsAt) { }

    public record RoomDetail(String roomId, String raceId, String raceName, int roundNumber, String status,
                             long onlineCount, Instant opensAt, Instant closesAt, long participantCount,
                             Instant weekendStartsAt, Instant weekendEndsAt, boolean canSendMessage) {
        public static RoomDetail from(Room room, boolean canSend) {
            return new RoomDetail(room.roomId(), room.raceId(), room.raceName(), room.roundNumber(), room.status(), room.onlineCount(),
                    room.opensAt(), room.closesAt(), room.participantCount(), room.weekendStartsAt(), room.weekendEndsAt(), canSend);
        }
    }

    public record RoomPage(List<Room> items, String nextCursor, boolean hasNext) { }
    public record Message(String messageId, String clientMessageId, String roomId, Author sender, String content,
                          Instant createdAt, boolean isDeleted, boolean canDelete) { }
    public record Marker(String markerId, String sessionId, String label, Instant occurredAt) { }
    public record MessagePage(List<Message> items, String nextCursor, boolean hasNext, List<Marker> timelineMarkers) { }
    public record HistoryMeta(String requestId, Instant serverTime, String eventCursor) { }
    public record History(MessagePage data, HistoryMeta meta) {
        public static History from(MessagePage page, long roomId, long sequence) {
            var base = ApiResponse.Meta.create();
            return new History(page, new HistoryMeta(base.requestId(), base.serverTime(), "evt_" + roomId + "_" + sequence));
        }
    }
}
