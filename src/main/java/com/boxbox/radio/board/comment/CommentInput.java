package com.boxbox.radio.board.comment;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.BoardFilter;
import java.util.Map;
import java.util.Set;

record CommentInput(String content, Long parentId) {
    static CommentInput from(Map<String, Object> body, boolean create) {
        Set<String> allowed = create ? Set.of("content", "parentId") : Set.of("content");
        if (body == null || !allowed.containsAll(body.keySet()) || !(body.get("content") instanceof String text)) {
            throw ApiException.invalidRequest("댓글 본문과 허용된 필드만 입력해 주세요.");
        }
        String content = text.strip();
        if (content.isBlank() || content.codePointCount(0, content.length()) > 1000
                || content.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            throw ApiException.invalidRequest("댓글 본문은 앞뒤 공백 제거 후 1~1000자여야 합니다.");
        }
        Long parent = null;
        if (create && body.get("parentId") != null) {
            if (!(body.get("parentId") instanceof String id)) {
                throw ApiException.invalidRequest("parentId는 문자열 ID여야 합니다.");
            }
            parent = BoardFilter.parseId(id, "parentId");
        }
        return new CommentInput(content, parent);
    }
}
