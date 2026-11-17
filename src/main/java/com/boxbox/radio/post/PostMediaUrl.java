package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class PostMediaUrl {
    private final String baseUrl;

    PostMediaUrl(@Value("${boxbox.posts.media-base-url:}") String baseUrl) {
        this.baseUrl = baseUrl.strip();
    }

    String resolve(String objectKey) {
        try {
            URI base = URI.create(baseUrl);
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null
                    || objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")
                    || objectKey.contains("\\")) {
                throw new IllegalArgumentException();
            }
            String path = Arrays.stream(objectKey.split("/", -1)).map(segment -> {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException();
                }
                return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
            }).collect(java.util.stream.Collectors.joining("/"));
            return baseUrl + (baseUrl.endsWith("/") ? "" : "/") + path;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE",
                    "첨부 이미지 조회 주소를 확인할 수 없습니다.");
        }
    }
}
