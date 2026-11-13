package com.boxbox.common.api;

import java.util.List;

public record ApiErrorResponse(Error error, ApiResponse.Meta meta) {
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new Error(code, message, List.of()), ApiResponse.Meta.create());
    }

    public record Error(String code, String message, List<Object> details) {
    }
}
