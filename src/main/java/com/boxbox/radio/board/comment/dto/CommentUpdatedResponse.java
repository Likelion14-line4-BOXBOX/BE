package com.boxbox.radio.board.comment.dto;

import java.time.Instant;

public record CommentUpdatedResponse(String commentId, String content, Instant updatedAt) { }
