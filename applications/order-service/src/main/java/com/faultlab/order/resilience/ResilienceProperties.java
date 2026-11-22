package com.faultlab.order.resilience;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultlab.resilience")
public record ResilienceProperties(Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {

    public enum RetryMode { NONE, MANUAL, RESILIENCE4J }

    public enum BackoffStrategy { FIXED, LINEAR, EXPONENTIAL }

    public enum Jitter { NONE, FULL, EQUAL }

    public enum SlidingWindowType { COUNT_BASED, TIME_BASED }

    public record Retry(boolean enabled, RetryMode mode, int maxAttempts, List<Integer> retryableStatusCodes,
                        List<String> retryableExceptions, BackoffStrategy backoffStrategy, Duration initialInterval,
                        double multiplier, Duration maxInterval, Jitter jitter) {

        public Retry {
            mode = mode == null ? RetryMode.RESILIENCE4J : mode;
            maxAttempts = Math.max(1, Math.min(10, maxAttempts));
            retryableStatusCodes = retryableStatusCodes == null ? List.of() : List.copyOf(retryableStatusCodes);
            retryableExceptions = retryableExceptions == null ? List.of() : List.copyOf(retryableExceptions);
            backoffStrategy = backoffStrategy == null ? BackoffStrategy.EXPONENTIAL : backoffStrategy;
            initialInterval = nonNegative(initialInterval, Duration.ofMillis(100));
            multiplier = multiplier < 1.0 || Double.isNaN(multiplier) ? 1.0 : multiplier;
            maxInterval = nonNegative(maxInterval, Duration.ofSeconds(2));
            jitter = jitter == null ? Jitter.NONE : jitter;
        }

        /** Effective mode: disabled retries behave like NONE. */
        public RetryMode effectiveMode() { return enabled ? mode : RetryMode.NONE; }
    }

    public record CircuitBreaker(boolean enabled, float failureRateThreshold, float slowCallRateThreshold,
                                 Duration slowCallDuration, SlidingWindowType slidingWindowType,
                                 int slidingWindowSize, int minimumNumberOfCalls, Duration waitDurationInOpenState,
                                 int permittedCallsInHalfOpenState, Duration maxWaitInHalfOpenState) {

        public CircuitBreaker {
            failureRateThreshold = clampPercent(failureRateThreshold, 50);
            slowCallRateThreshold = clampPercent(slowCallRateThreshold, 100);
            slowCallDuration = positive(slowCallDuration, Duration.ofSeconds(2));
            slidingWindowType = slidingWindowType == null ? SlidingWindowType.COUNT_BASED : slidingWindowType;
            slidingWindowSize = Math.max(2, slidingWindowSize);
            minimumNumberOfCalls = Math.max(1, minimumNumberOfCalls);
            waitDurationInOpenState = positive(waitDurationInOpenState, Duration.ofSeconds(10));
            permittedCallsInHalfOpenState = Math.max(1, permittedCallsInHalfOpenState);
            maxWaitInHalfOpenState = nonNegative(maxWaitInHalfOpenState, Duration.ZERO);
        }
    }

    public record Bulkhead(boolean enabled, int maxConcurrentCalls, Duration maxWaitDuration) {
        public Bulkhead {
            maxConcurrentCalls = Math.max(1, maxConcurrentCalls);
            maxWaitDuration = nonNegative(maxWaitDuration, Duration.ZERO);
        }
    }

    private static Duration nonNegative(Duration d, Duration fallback) {
        return d == null || d.isNegative() ? fallback : d;
    }

    private static Duration positive(Duration d, Duration fallback) {
        return d == null || d.isNegative() || d.isZero() ? fallback : d;
    }

    private static float clampPercent(float v, float fallback) {
        return v <= 0 || v > 100 || Float.isNaN(v) ? fallback : v;
    }
}
