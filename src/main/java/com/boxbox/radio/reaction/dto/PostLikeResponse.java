package com.boxbox.radio.reaction.dto;

public record PostLikeResponse(String postId, boolean likedByMe, long likeCount) { }
