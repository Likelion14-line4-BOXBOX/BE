package com.boxbox.radio.board.dto;

public record BoardTagResponse(
        String tagId,
        String name,
        String nameKo,
        String group,
        boolean isActive,
        int displayOrder
) {
}
