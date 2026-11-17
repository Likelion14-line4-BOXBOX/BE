package com.boxbox.radio.reaction.dto;

public record CommentLikeResponse(String commentId, boolean likedByMe, long likeCount) { }
