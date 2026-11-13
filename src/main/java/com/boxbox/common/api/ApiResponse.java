package com.boxbox.common.api;

import java.time.Instant;
import java.util.UUID;

public record ApiResponse<T>(T data, Meta meta) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, Meta.create());
    }

    public record Meta(String requestId, Instant serverTime) {
        public static Meta create() {
            return new Meta(UUID.randomUUID().toString(), Instant.now());
        }
    }
}
