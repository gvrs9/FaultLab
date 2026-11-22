package com.faultlab.order.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.faultlab.order.inventory.DownstreamHttpException;
import com.faultlab.order.inventory.DownstreamRejectedException;
import com.faultlab.order.resilience.ResilienceProperties.BackoffStrategy;
import com.faultlab.order.resilience.ResilienceProperties.Jitter;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import com.faultlab.order.support.TestResilience;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
            TestResilience.retry(RetryMode.MANUAL, 3, BackoffStrategy.FIXED, Jitter.NONE));

    @Test
    void retriesConfiguredStatusCodesOnly() {
        assertThat(policy.isRetryable(new DownstreamHttpException(503, ""))).isTrue();
        assertThat(policy.isRetryable(new DownstreamHttpException(504, ""))).isTrue();
        assertThat(policy.isRetryable(new DownstreamHttpException(500, ""))).isFalse();
        assertThat(policy.isRetryable(new DownstreamHttpException(429, ""))).isFalse();
    }

    @Test
    void neverRetriesBusinessRejections() {
        assertThat(policy.isRetryable(new DownstreamRejectedException(409, ""))).isFalse();
        assertThat(policy.isRetryable(new DownstreamRejectedException(422, ""))).isFalse();
    }

    @Test
    void retriesConfiguredExceptionsInCauseChainIncludingSubclasses() {
        assertThat(policy.isRetryable(new ResourceAccessException("x", new ConnectException("refused")))).isTrue();
        assertThat(policy.isRetryable(new ResourceAccessException("x", new HttpTimeoutException("t")))).isTrue();
        assertThat(policy.isRetryable(new ResourceAccessException("x", new HttpConnectTimeoutException("t")))).isTrue();
    }

    @Test
    void doesNotRetryUnlistedExceptions() {
        assertThat(policy.isRetryable(new IllegalStateException("bug"))).isFalse();
        assertThat(policy.isRetryable(new ResourceAccessException("x", new java.io.IOException("other")))).isFalse();
        assertThat(policy.isRetryable(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("t")))).isFalse();
    }

    @Test
    void ignoresUnknownConfiguredClassNames() {
        var cfg = new ResilienceProperties.Retry(true, RetryMode.MANUAL, 3, java.util.List.of(503),
                java.util.List.of("com.example.DoesNotExist", " java.net.ConnectException "), BackoffStrategy.FIXED,
                java.time.Duration.ofMillis(1), 1, java.time.Duration.ofMillis(1), Jitter.NONE);
        assertThat(new RetryPolicy(cfg).isRetryable(new ConnectException())).isTrue();
    }
}
