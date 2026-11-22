package com.faultlab.order.resilience;

import com.faultlab.order.inventory.DownstreamHttpException;
import com.faultlab.order.inventory.DownstreamRejectedException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.ResourceAccessException;

/**
 * Builds Resilience4j components from {@link ResilienceProperties}. Uses only the Resilience4j core modules
 * (no annotations/AOP), so behaviour is explicit and identical in Spring and in plain unit tests.
 */
public final class ResilienceFactory {

    public static final String INVENTORY = "inventory";

    private ResilienceFactory() { }

    public static CircuitBreaker circuitBreaker(ResilienceProperties.CircuitBreaker c) {
        return CircuitBreaker.of(INVENTORY, circuitBreakerConfig(c));
    }

    public static CircuitBreakerConfig circuitBreakerConfig(ResilienceProperties.CircuitBreaker c) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(c.slidingWindowType() == ResilienceProperties.SlidingWindowType.TIME_BASED
                        ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                        : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(c.slidingWindowSize())
                .minimumNumberOfCalls(c.minimumNumberOfCalls())
                .failureRateThreshold(c.failureRateThreshold())
                .slowCallRateThreshold(c.slowCallRateThreshold())
                .slowCallDurationThreshold(c.slowCallDuration())
                .waitDurationInOpenState(c.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(c.permittedCallsInHalfOpenState())
                .maxWaitDurationInHalfOpenState(c.maxWaitInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only infrastructure-level failures count; 4xx business answers do not.
                .recordException(t -> t instanceof DownstreamHttpException || t instanceof ResourceAccessException)
                .ignoreExceptions(DownstreamRejectedException.class)
                .build();
    }

    public static Retry retry(ResilienceProperties.Retry r, RetryPolicy policy, BackoffCalculator backoff) {
        return Retry.of(INVENTORY, retryConfig(r, policy, backoff));
    }

    public static RetryConfig retryConfig(ResilienceProperties.Retry r, RetryPolicy policy, BackoffCalculator backoff) {
        IntervalFunction interval = attempt -> backoff.delayBeforeRetry(attempt).toMillis();
        return RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                .intervalFunction(interval)
                .retryOnException(policy::isRetryable)
                .failAfterMaxAttempts(false)
                .build();
    }

    public static Bulkhead bulkhead(ResilienceProperties.Bulkhead b) {
        return Bulkhead.of(INVENTORY, bulkheadConfig(b));
    }

    public static BulkheadConfig bulkheadConfig(ResilienceProperties.Bulkhead b) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(b.maxConcurrentCalls())
                .maxWaitDuration(b.maxWaitDuration())
                .build();
    }
}
