package com.boxbox.radio.board.comment;

import com.boxbox.common.api.ApiException;
import java.util.Map;
import java.util.Set;

record CommentPageRequest(int size, String cursor) {
    static CommentPageRequest from(Map<String, String> params) {
        if (!Set.of("size", "cursor").containsAll(params.keySet())) {
            throw ApiException.invalidRequest("지원하지 않는 댓글 조회 조건입니다.");
        }
        int size = 20;
        if (params.containsKey("size")) {
            String value = params.get("size");
            if (!value.matches("[0-9]{1,3}") || (size = Integer.parseInt(value)) < 1 || size > 100) {
                throw ApiException.invalidRequest("size는 1~100의 정수여야 합니다.");
            }
        }
        String cursor = params.get("cursor");
        if (cursor != null && (cursor.isBlank() || cursor.length() > 1024)) {
            throw ApiException.invalidRequest("올바른 댓글 커서를 입력해 주세요.");
        }
        return new CommentPageRequest(size, cursor);
    }
}
