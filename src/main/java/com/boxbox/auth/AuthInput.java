package com.boxbox.auth;

import com.boxbox.common.api.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AuthInput {
    record Signup(String loginId, String password, String nickname) {
        @Override public String toString() { return "Signup[redacted]"; }
    }
    record Login(String loginId, String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }

    static Signup signup(Map<String, Object> body) {
        fields(body, Set.of("loginId", "password", "nickname"));
        String nickname = text(body, "nickname").trim();
        length(nickname, 2, 20, "닉네임");
        return new Signup(loginId(body), password(body, 8), nickname);
    }

    static Login login(Map<String, Object> body) {
        fields(body, Set.of("loginId", "password"));
        return new Login(loginId(body), password(body, 1));
    }

    private static String loginId(Map<String, Object> body) {
        String value = text(body, "loginId").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_]{4,20}")) throw ApiException.invalidRequest("아이디는 영문/숫자/_ 4~20자여야 합니다.");
        return value;
    }

    private static String password(Map<String, Object> body, int minimum) {
        String value = text(body, "password");
        length(value, minimum, 64, "비밀번호");
        return value;
    }

    private static void fields(Map<String, Object> body, Set<String> fields) {
        if (body == null || !body.keySet().equals(fields)) throw ApiException.invalidRequest("요청 필드를 확인해 주세요.");
    }

    private static String text(Map<String, Object> body, String name) {
        if (!(body.get(name) instanceof String value)) throw ApiException.invalidRequest(name + "는 문자열이어야 합니다.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw ApiException.invalidRequest("문자 인코딩을 확인해 주세요.");
            } else if (Character.isLowSurrogate(c)) throw ApiException.invalidRequest("문자 인코딩을 확인해 주세요.");
        }
        return value;
    }

    private static void length(String value, int min, int max, String field) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw ApiException.invalidRequest(field + "는 " + min + "~" + max + "자여야 합니다.");
    }
}
