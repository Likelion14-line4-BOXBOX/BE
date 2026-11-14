package com.boxbox.radio.report;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.BoardFilter;
import java.util.Map;
import java.util.Set;

record ReportInput(TargetType targetType, long targetId, Reason reason, String description) {
    enum TargetType { POST, COMMENT, CHAT_MESSAGE }
    enum Reason { SPAM, ABUSE, INAPPROPRIATE, OTHER }

    static ReportInput from(Map<String, Object> body) {
        if (body == null || !Set.of("targetType", "targetId", "reason", "description").containsAll(body.keySet())
                || !(body.get("targetType") instanceof String type) || !(body.get("targetId") instanceof String id)
                || !(body.get("reason") instanceof String reason)) {
            throw ApiException.invalidRequest("신고 대상 유형·ID·사유와 허용된 필드만 입력해 주세요.");
        }
        TargetType target;
        Reason cause;
        try { target = TargetType.valueOf(type); cause = Reason.valueOf(reason); }
        catch (IllegalArgumentException error) { throw ApiException.invalidRequest("신고 대상 유형 또는 사유를 확인해 주세요."); }
        String description = null;
        if (body.containsKey("description")) {
            if (!(body.get("description") instanceof String text)) throw ApiException.invalidRequest("description은 null이 아닌 문자열이어야 합니다.");
            description = text.strip();
            if (description.codePointCount(0, description.length()) > 1000
                    || description.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
                throw ApiException.invalidRequest("상세 사유는 올바른 문자열로 1000자 이하로 입력해 주세요.");
            }
            if (description.isBlank()) description = null;
        }
        if (cause == Reason.OTHER && description == null) throw ApiException.invalidRequest("OTHER 사유에는 상세 설명이 필요합니다.");
        return new ReportInput(target, BoardFilter.parseId(id, "targetId"), cause, description);
    }
}
