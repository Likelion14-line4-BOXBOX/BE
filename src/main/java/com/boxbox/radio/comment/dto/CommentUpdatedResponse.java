package com.boxbox.radio.comment.dto;

import java.time.Instant;

public record CommentUpdatedResponse(String commentId, String content, Instant updatedAt) { }
