package com.boxbox.radio.board.dto;

public record BoardTagResponse(
        String tagId,
        String name,
        String group,
        boolean isActive,
        int displayOrder
) {
}
