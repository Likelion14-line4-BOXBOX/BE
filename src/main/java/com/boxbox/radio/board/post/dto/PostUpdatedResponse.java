package com.boxbox.radio.board.post.dto;

import java.time.Instant;

public record PostUpdatedResponse(String postId, Instant updatedAt) { }
