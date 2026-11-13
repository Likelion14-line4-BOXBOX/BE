package com.boxbox.radio.board.reaction.dto;

public record CommentLikeResponse(String commentId, boolean likedByMe, long likeCount) { }
