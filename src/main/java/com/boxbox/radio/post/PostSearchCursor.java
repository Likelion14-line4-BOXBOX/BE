package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record PostSearchCursor(Instant anchor, long maxId, long score, Instant createdAt, long id) {
    static PostSearchCursor decode(String value, PostSearchFilter filter, Instant now) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 7 || !parts[0].equals("1") || !parts[1].equals(filter.fingerprint())
                    || !parts[3].matches("[0-9]{1,19}") || !parts[4].matches("[0-9]{1,19}")
                    || !parts[6].matches("[0-9]{1,19}")) {
                throw new IllegalArgumentException();
            }
            var cursor = new PostSearchCursor(Instant.parse(parts[2]), Long.parseLong(parts[3]), Long.parseLong(parts[4]),
                    Instant.parse(parts[5]), Long.parseLong(parts[6]));
            if (cursor.id <= 0 || cursor.maxId < cursor.id || cursor.score < 0 || cursor.anchor.isAfter(now)
                    || cursor.createdAt.isAfter(cursor.anchor) || cursor.createdAt.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || ((filter.sort() == PostSearchFilter.Sort.LATEST || filter.sort() == PostSearchFilter.Sort.OLDEST)
                        && cursor.score != 0)) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (RuntimeException exception) {
            throw ApiException.invalidRequest("같은 검색 조건에서 발급된 올바른 커서를 사용해 주세요.");
        }
    }

    String encode(PostSearchFilter filter) {
        String payload = "1|" + filter.fingerprint() + "|" + anchor + "|" + maxId + "|" + score + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
