package com.boxbox.radio.board.reaction.dto;

public record PostLikeResponse(String postId, boolean likedByMe, long likeCount) { }
