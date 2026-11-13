package com.boxbox.radio.board.reaction.dto;

public record PostBookmarkResponse(String postId, boolean bookmarkedByMe, long bookmarkCount) { }
