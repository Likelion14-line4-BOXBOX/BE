package com.boxbox.garage.profile;

import com.boxbox.common.api.ApiException;
import java.util.Map;
import java.util.Set;

/** JSON 키의 존재 여부를 보관해 PATCH의 생략과 명시적인 null을 구분한다. */
record ProfilePatch(Set<String> fields, String nickname, String bio, String avatarAssetId,
                    Long favoriteTeamId, Long favoriteDriverId, Boolean showMarketAssets) {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "nickname", "bio", "avatarAssetId", "favoriteTeamId", "favoriteDriverId", "showMarketAssets");

    static ProfilePatch from(Map<String, Object> body) {
        if (!ALLOWED_FIELDS.containsAll(body.keySet())) {
            throw ApiException.invalidRequest("수정할 수 없는 필드가 포함되어 있습니다.");
        }
        String nickname = string(body, "nickname", false);
        if (nickname != null) {
            nickname = nickname.trim();
            int length = nickname.codePointCount(0, nickname.length());
            if (length < 2 || length > 20) {
                throw ApiException.invalidRequest("닉네임은 앞뒤 공백 제거 후 2~20자여야 합니다.");
            }
        }
        String bio = string(body, "bio", false);
        if (bio != null && bio.codePointCount(0, bio.length()) > 100) {
            throw ApiException.invalidRequest("소개는 100자 이하여야 합니다.");
        }
        Boolean showMarketAssets = null;
        if (body.containsKey("showMarketAssets")) {
            if (!(body.get("showMarketAssets") instanceof Boolean value)) {
                throw ApiException.invalidRequest("showMarketAssets는 Boolean이어야 합니다.");
            }
            showMarketAssets = value;
        }
        return new ProfilePatch(Set.copyOf(body.keySet()), nickname, bio,
                string(body, "avatarAssetId", true), id(body, "favoriteTeamId"),
                id(body, "favoriteDriverId"), showMarketAssets);
    }

    boolean has(String field) {
        return fields.contains(field);
    }

    private static String string(Map<String, Object> body, String field, boolean nullable) {
        Object value = body.get(field);
        if (!body.containsKey(field) || (value == null && nullable)) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw ApiException.invalidRequest(field + "는 문자열이어야 합니다.");
        }
        return text;
    }

    private static Long id(Map<String, Object> body, String field) {
        String text = string(body, field, true);
        if (text == null) {
            return null;
        }
        if (!text.matches("[1-9][0-9]*")) {
            throw ApiException.invalidRequest(field + "는 양의 정수 ID 문자열이어야 합니다.");
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            throw ApiException.invalidRequest(field + "의 범위를 확인해 주세요.");
        }
    }
}
