package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import java.util.Map;
import java.util.Set;

record ChatPageRequest(ChatRoomState status, int season, int size, String cursor) {
    static ChatPageRequest rooms(Map<String, String> params, int activeSeason) {
        return parse(params, activeSeason, Set.of("status", "season", "size", "cursor"));
    }

    static ChatPageRequest messages(Map<String, String> params) {
        return parse(params, 0, Set.of("size", "cursor"));
    }

    private static ChatPageRequest parse(Map<String, String> params, int activeSeason, Set<String> allowed) {
        if (!allowed.containsAll(params.keySet())) throw ApiException.invalidRequest("지원하지 않는 채팅 조회 조건입니다.");
        ChatRoomState status = null;
        if (params.containsKey("status")) {
            try { status = ChatRoomState.valueOf(params.get("status")); }
            catch (IllegalArgumentException error) { throw ApiException.invalidRequest("status는 SCHEDULED, LIVE, ARCHIVED 중 하나여야 합니다."); }
        }
        int season = activeSeason;
        if (params.containsKey("season")) {
            String value = params.get("season");
            if (!value.matches("[0-9]{4}") || (season = Integer.parseInt(value)) < 1000) throw ApiException.invalidRequest("season은 네 자리 시즌 연도여야 합니다.");
        }
        int size = 20;
        if (params.containsKey("size")) {
            String value = params.get("size");
            if (!value.matches("[0-9]{1,3}") || (size = Integer.parseInt(value)) < 1 || size > 100) throw ApiException.invalidRequest("size는 1~100이어야 합니다.");
        }
        String cursor = params.get("cursor");
        if (cursor != null && (cursor.isBlank() || cursor.length() > 1024)) throw ApiException.invalidRequest("올바른 커서를 입력해 주세요.");
        return new ChatPageRequest(status, season, size, cursor);
    }

    String statusKey() { return status == null ? "ALL" : status.name(); }
}
