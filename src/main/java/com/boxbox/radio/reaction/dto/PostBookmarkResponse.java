package com.boxbox.radio.reaction.dto;

public record PostBookmarkResponse(String postId, boolean bookmarkedByMe, long bookmarkCount) { }
