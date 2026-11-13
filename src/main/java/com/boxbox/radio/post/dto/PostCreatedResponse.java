package com.boxbox.radio.post.dto;

import java.time.Instant;

public record PostCreatedResponse(String postId, String boardId, Instant createdAt) { }
