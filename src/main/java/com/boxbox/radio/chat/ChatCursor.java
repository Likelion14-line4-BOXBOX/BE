package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

final class ChatCursor {
    private ChatCursor() { }

    record Rooms(Instant anchor, long maxId, Instant closesAt, long id) {
        static Rooms decode(String value, ChatPageRequest request, Instant now) {
            try {
                String[] parts = unpack(value);
                if (parts.length != 8 || !parts[0].equals("rooms-1") || !parts[1].equals(request.statusKey())
                        || !parts[2].equals(Integer.toString(request.season())) || !parts[3].equals(Integer.toString(request.size()))) throw new IllegalArgumentException();
                var cursor = new Rooms(time(parts[4]), number(parts[5]), time(parts[6]), number(parts[7]));
                if (cursor.anchor.isAfter(now) || cursor.id <= 0 || cursor.maxId < cursor.id) throw new IllegalArgumentException();
                return cursor;
            } catch (RuntimeException error) { throw invalid(); }
        }

        String encode(ChatPageRequest request) {
            return pack("rooms-1|" + request.statusKey() + "|" + request.season() + "|" + request.size() + "|" + anchor + "|" + maxId + "|" + closesAt + "|" + id);
        }
    }

    record Messages(Instant anchor, long maxId, long eventSequence, Instant createdAt, long id) {
        static Messages decode(String value, long roomId, int size, long currentSequence, Instant now) {
            try {
                String[] parts = unpack(value);
                if (parts.length != 8 || !parts[0].equals("messages-1") || !parts[1].equals(Long.toString(roomId))
                        || !parts[2].equals(Integer.toString(size))) throw new IllegalArgumentException();
                var cursor = new Messages(time(parts[3]), number(parts[4]), number(parts[5]), time(parts[6]), number(parts[7]));
                if (cursor.anchor.isAfter(now) || cursor.createdAt.isAfter(cursor.anchor) || cursor.eventSequence > currentSequence
                        || cursor.id <= 0 || cursor.maxId < cursor.id) throw new IllegalArgumentException();
                return cursor;
            } catch (RuntimeException error) { throw invalid(); }
        }

        String encode(long roomId, int size) {
            return pack("messages-1|" + roomId + "|" + size + "|" + anchor + "|" + maxId + "|" + eventSequence + "|" + createdAt + "|" + id);
        }
    }

    private static String pack(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String[] unpack(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1); }
    private static long number(String value) {
        if (!value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private static Instant time(String value) {
        Instant instant = Instant.parse(value);
        if (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new IllegalArgumentException();
        return instant;
    }
    private static ApiException invalid() { return ApiException.invalidRequest("같은 조회 조건에서 발급된 올바른 채팅 커서를 사용해 주세요."); }
}
