package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.BoardFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record PostInput(Set<String> fields, String title, String content, List<Long> tagIds, List<Long> mediaIds) {
    static PostInput from(Map<String, Object> body, boolean create) {
        Set<String> allowed = Set.of("title", "content", "tagIds", "mediaIds");
        if (body == null || !allowed.containsAll(body.keySet())) {
            throw ApiException.invalidRequest("title, content, tagIds, mediaIds만 입력할 수 있습니다.");
        }
        if (create && (!body.containsKey("title") || !body.containsKey("content"))) {
            throw ApiException.invalidRequest("제목과 본문은 필수입니다.");
        }
        String title = text(body, "title");
        String content = text(body, "content");
        if (title != null) {
            title = title.strip();
            if (title.isBlank() || title.codePointCount(0, title.length()) > 20) {
                throw ApiException.invalidRequest("제목은 앞뒤 공백 제거 후 1~20자여야 합니다.");
            }
        }
        if (content != null && content.isBlank()) {
            throw ApiException.invalidRequest("본문은 공백만 입력할 수 없습니다.");
        }
        return new PostInput(Set.copyOf(body.keySet()), title, content,
                ids(body, "tagIds", 1), ids(body, "mediaIds", 5));
    }

    boolean has(String name) { return fields.contains(name); }

    private static String text(Map<String, Object> body, String field) {
        if (!body.containsKey(field)) return null;
        if (!(body.get(field) instanceof String value)
                || value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            throw ApiException.invalidRequest(field + "는 null이 아닌 올바른 문자열이어야 합니다.");
        }
        return value;
    }

    private static List<Long> ids(Map<String, Object> body, String field, int max) {
        if (!body.containsKey(field)) return List.of();
        if (!(body.get(field) instanceof List<?> values) || values.size() > max) {
            throw ApiException.invalidRequest(field + "는 최대 " + max + "개의 ID 배열이어야 합니다.");
        }
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String id)) {
                throw ApiException.invalidRequest(field + "의 ID는 문자열이어야 합니다.");
            }
            result.add(BoardFilter.parseId(id, field));
        }
        if (new HashSet<>(result).size() != result.size()) {
            throw ApiException.invalidRequest(field + "에 중복 ID를 입력할 수 없습니다.");
        }
        return List.copyOf(result);
    }
}
