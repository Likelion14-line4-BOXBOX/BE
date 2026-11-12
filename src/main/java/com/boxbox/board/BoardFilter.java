package com.boxbox.board;

import com.boxbox.common.api.ApiException;

public record BoardFilter(BoardType type, Long teamId) {
    public static BoardFilter from(String type, String teamId) {
        BoardType boardType = null;
        if (type != null) {
            try {
                boardType = BoardType.valueOf(type);
            } catch (IllegalArgumentException exception) {
                throw ApiException.invalidRequest("type은 TEAM, BEGINNER, FREE 중 하나여야 합니다.");
            }
        }

        Long parsedTeamId = teamId == null ? null : parseId(teamId, "teamId");
        if (parsedTeamId != null && boardType != null && boardType != BoardType.TEAM) {
            throw ApiException.invalidRequest("teamId는 팀 게시판 조회에서만 사용할 수 있습니다.");
        }
        return new BoardFilter(boardType, parsedTeamId);
    }

    public static long parseId(String value, String field) {
        try {
            if (value == null || !value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw ApiException.invalidRequest(field + "는 유효한 양의 정수 ID여야 합니다.");
        }
    }
}
