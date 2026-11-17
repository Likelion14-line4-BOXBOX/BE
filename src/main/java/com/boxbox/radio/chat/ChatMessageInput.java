package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

record ChatMessageInput(String clientMessageId, String content, byte[] hash) {
    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    static ChatMessageInput parse(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("clientMessageId", "content"))
                || !(body.get("clientMessageId") instanceof String id) || !UUID_FORMAT.matcher(id).matches()
                || !(body.get("content") instanceof String raw)) {
            throw ApiException.invalidRequest("clientMessageId(UUID)와 content를 입력해 주세요.");
        }
        String content = raw.strip();
        if (content.isBlank() || content.codePointCount(0, content.length()) > 500 || !validUnicode(content)) {
            throw ApiException.invalidRequest("content는 공백을 제외하고 1~500자로 입력해 주세요.");
        }
        try {
            return new ChatMessageInput(UUID.fromString(id).toString(), content,
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }
}
