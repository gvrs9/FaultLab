package com.faultlab.order.support;

import com.faultlab.order.resilience.ResilienceProperties;
import com.faultlab.order.resilience.ResilienceProperties.BackoffStrategy;
import com.faultlab.order.resilience.ResilienceProperties.Jitter;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import com.faultlab.order.resilience.ResilienceProperties.SlidingWindowType;
import java.time.Duration;
import java.util.List;

public final class TestResilience {

    private TestResilience() { }

    public static ResilienceProperties.Retry retry(RetryMode mode, int maxAttempts, BackoffStrategy strategy,
                                                   Jitter jitter) {
        return new ResilienceProperties.Retry(true, mode, maxAttempts, List.of(502, 503, 504),
                List.of("java.net.ConnectException", "java.net.http.HttpTimeoutException",
                        "java.net.SocketTimeoutException"),
                strategy, Duration.ofMillis(10), 2.0, Duration.ofMillis(50), jitter);
    }

    public static ResilienceProperties props(RetryMode mode, int maxAttempts, boolean cbEnabled, int cbWindow,
                                             boolean bulkheadEnabled, int bulkheadMax) {
        return new ResilienceProperties(
                retry(mode, maxAttempts, BackoffStrategy.FIXED, Jitter.NONE),
                new ResilienceProperties.CircuitBreaker(cbEnabled, 50, 100, Duration.ofSeconds(5),
                        SlidingWindowType.COUNT_BASED, cbWindow, cbWindow, Duration.ofSeconds(30), 1, Duration.ZERO),
                new ResilienceProperties.Bulkhead(bulkheadEnabled, bulkheadMax, Duration.ZERO));
    }
}
