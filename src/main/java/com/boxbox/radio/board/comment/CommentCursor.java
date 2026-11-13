package com.boxbox.radio.board.comment;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record CommentCursor(long maxId, Instant anchor, Instant createdAt, long id) {
    static CommentCursor decode(String value, long postId, int size, Instant now) {
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 7 || !fields[0].equals("1") || !fields[1].equals(Long.toString(postId))
                    || !fields[2].equals(Integer.toString(size)) || !fields[3].matches("[0-9]{1,19}")
                    || !fields[6].matches("[0-9]{1,19}")) throw new IllegalArgumentException();
            var cursor = new CommentCursor(Long.parseLong(fields[3]), Instant.parse(fields[4]),
                    Instant.parse(fields[5]), Long.parseLong(fields[6]));
            if (cursor.id <= 0 || cursor.maxId < cursor.id || cursor.anchor.isAfter(now)
                    || cursor.createdAt.isAfter(cursor.anchor) || cursor.createdAt.isBefore(Instant.parse("1000-01-01T00:00:00Z"))) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (RuntimeException exception) {
            throw ApiException.invalidRequest("같은 게시글과 페이지 크기로 발급된 올바른 댓글 커서를 사용해 주세요.");
        }
    }

    String encode(long postId, int size) {
        String value = "1|" + postId + "|" + size + "|" + maxId + "|" + anchor + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
