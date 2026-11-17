package com.boxbox.auth;

import static org.assertj.core.api.Assertions.*;
import com.boxbox.common.api.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTests {
    @Test void requestsShareLimitAcrossThreadsAndWindowExpires() throws Exception {
        var clock = new AuthApiTests.MutableClock();
        var limiter = new AuthRateLimiter(clock);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> {
                start.await();
                try { limiter.acquire("login", "shared", 5); return true; }
                catch (ApiException error) { assertThat(error.getCode()).isEqualTo("RATE_LIMITED"); return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertThat(successes).isEqualTo(5);
        }
        clock.advance(Duration.ofSeconds(60));
        assertThatCode(() -> limiter.acquire("login", "shared", 5)).doesNotThrowAnyException();
    }
}
