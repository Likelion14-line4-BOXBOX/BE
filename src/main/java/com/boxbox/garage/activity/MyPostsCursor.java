package com.boxbox.garage.activity;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record MyPostsCursor(Instant anchor, long maxPostId, Instant activityAt, long postId) {
    static MyPostsCursor decode(String value, long viewer, MyPostsRequest request, Instant now) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 8 || !parts[0].equals("my-posts-1") || !parts[1].equals(Long.toString(viewer))
                    || !parts[2].equals(request.filter().name()) || !parts[3].equals(Integer.toString(request.size()))
                    || !parts[5].matches("[0-9]{1,19}") || !parts[7].matches("[0-9]{1,19}")) {
                throw new IllegalArgumentException();
            }
            var cursor = new MyPostsCursor(Instant.parse(parts[4]), Long.parseLong(parts[5]),
                    Instant.parse(parts[6]), Long.parseLong(parts[7]));
            if (cursor.postId <= 0 || cursor.maxPostId < cursor.postId || cursor.anchor.isAfter(now)
                    || cursor.activityAt.isAfter(cursor.anchor)
                    || cursor.activityAt.isBefore(Instant.parse("1000-01-01T00:00:00Z"))) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (RuntimeException error) {
            throw ApiException.invalidRequest("같은 회원·필터·페이지 크기에서 발급된 올바른 커서를 사용해 주세요.");
        }
    }

    String encode(long viewer, MyPostsRequest request) {
        String value = "my-posts-1|" + viewer + "|" + request.filter() + "|" + request.size() + "|"
                + anchor + "|" + maxPostId + "|" + activityAt + "|" + postId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
