package com.boxbox.race;

/** 세션 식별값은 유지하고 알려진 세션 종류만 한국어 표시명으로 변환한다. */
public final class SessionNames {
    private SessionNames() { }

    public static String display(String type, int number, String original) {
        if (original != null && original.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3)) return original;
        if ("PRACTICE".equals(type) && number >= 1 && number <= 3) return "프리 프랙티스 " + number;
        if (number != 1 || type == null) return original;
        return switch (type) {
            case "QUALIFYING" -> "예선";
            case "SPRINT_QUALIFYING" -> "스프린트 예선";
            case "SPRINT" -> "스프린트";
            case "RACE" -> "본선";
            default -> original;
        };
    }
}
