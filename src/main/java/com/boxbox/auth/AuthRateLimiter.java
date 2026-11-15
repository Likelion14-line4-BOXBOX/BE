package com.boxbox.auth;

import com.boxbox.common.api.ApiException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 단일 서버의 1분 고정 창 제한. 프록시가 보낸 임의의 X-Forwarded-For는 사용하지 않는다. */
@Component
@RequiredArgsConstructor
class AuthRateLimiter {
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private record Bucket(long expires, int count) {}

    synchronized void acquire(String operation, String value, int limit) {
        long now = clock.instant().getEpochSecond();
        buckets.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
        String key = operation + ":" + HexFormat.of().formatHex(AuthTokens.hash(value));
        Bucket bucket = buckets.get(key);
        if (bucket == null && buckets.size() >= 20_000 || bucket != null && bucket.count() >= limit)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "잠시 후 다시 시도해 주세요.");
        buckets.put(key, new Bucket(bucket == null ? now + 60 : bucket.expires(), bucket == null ? 1 : bucket.count() + 1));
    }
}
