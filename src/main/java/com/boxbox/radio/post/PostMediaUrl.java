package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Component
class PostMediaUrl {
    static final String UPLOAD_PREFIX = "post-images/";
    static final String UPLOAD_FILENAME = "[0-9a-f-]{36}\\.(jpg|png|webp)";
    private final String baseUrl;
    private final String publicBaseUrl;

    PostMediaUrl(@Value("${boxbox.posts.media-base-url:}") String baseUrl,
                 @Value("${boxbox.posts.media-public-base-url:}") String publicBaseUrl) {
        this.baseUrl = baseUrl.strip();
        this.publicBaseUrl = publicBaseUrl.strip();
    }

    String resolve(String objectKey) {
        if (objectKey != null && objectKey.startsWith(UPLOAD_PREFIX)
                && objectKey.substring(UPLOAD_PREFIX.length()).matches(UPLOAD_FILENAME)) {
            String path = "/api/media/" + objectKey;
            return publicBaseUrl.isEmpty()
                    ? ServletUriComponentsBuilder.fromCurrentContextPath().path(path).build().toUriString()
                    : publicBaseUrl + path;
        }
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
