package com.boxbox.radio.post.dto;

import java.time.Instant;

public record PostUpdatedResponse(String postId, Instant updatedAt) { }
