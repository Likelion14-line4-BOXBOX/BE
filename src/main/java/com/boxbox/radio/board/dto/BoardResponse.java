package com.boxbox.radio.board.dto;

public record BoardResponse(
        String boardId,
        String type,
        String name,
        String teamId,
        long postCount,
        int displayOrder,
        Boolean isMyTeam
) {
}
