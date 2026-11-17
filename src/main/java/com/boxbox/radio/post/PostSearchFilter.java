package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.radio.board.BoardFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

record PostSearchFilter(boolean boardOnly, Long boardId, Scope scope, String query, Long tagId,
                        Sort sort, Period period, int size, String cursor) {
    enum Scope { ACTIVE, ARCHIVE }
    enum Sort { LATEST, OLDEST, POPULAR, COMMENTS }
    enum Period { ALL, DAY, WEEK, MONTH, YEAR }

    static PostSearchFilter from(Long pathBoardId, Map<String, String> params) {
        Set<String> allowed = pathBoardId == null
                ? Set.of("boardId", "scope", "q", "tagId", "sort", "period", "size", "cursor")
                : Set.of("q", "tagId", "sort", "period", "size", "cursor");
        if (!allowed.containsAll(params.keySet())) throw ApiException.invalidRequest("지원하지 않는 검색 조건입니다.");
        Long boardId = pathBoardId != null ? pathBoardId : id(params, "boardId");
        String q = params.get("q");
        if (q != null) {
            q = q.strip();
            if (q.isBlank() || q.codePointCount(0, q.length()) > 100
                    || q.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
                throw ApiException.invalidRequest("검색어는 앞뒤 공백 제거 후 1~100자여야 합니다.");
            }
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
            throw ApiException.invalidRequest("올바른 커서를 입력해 주세요.");
        }
        return new PostSearchFilter(pathBoardId != null, boardId,
                option(params, "scope", Scope.ACTIVE, Scope.class), q, id(params, "tagId"),
                option(params, "sort", Sort.LATEST, Sort.class),
                option(params, "period", Period.ALL, Period.class), size, cursor);
    }

    Instant since(Instant anchor) {
        var utc = anchor.atZone(ZoneOffset.UTC);
        return switch (period) {
            case ALL -> null;
            case DAY -> utc.minusDays(1).toInstant();
            case WEEK -> utc.minusWeeks(1).toInstant();
            case MONTH -> utc.minusMonths(1).toInstant();
            case YEAR -> utc.minusYears(1).toInstant();
        };
    }

    String fingerprint() {
        String criteria = boardOnly + "|" + boardId + "|" + scope + "|" + tagId + "|"
                + sort + "|" + period + "|" + size + "|" + (query == null ? "" : query);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(criteria.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Long id(Map<String, String> params, String name) {
        return params.containsKey(name) ? BoardFilter.parseId(params.get(name), name) : null;
    }

    private static <T extends Enum<T>> T option(Map<String, String> params, String name, T fallback, Class<T> type) {
        if (!params.containsKey(name)) return fallback;
        try {
            return Enum.valueOf(type, params.get(name));
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalidRequest(name + " 값이 올바르지 않습니다.");
        }
    }
}
