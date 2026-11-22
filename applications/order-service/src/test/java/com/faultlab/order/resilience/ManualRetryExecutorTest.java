package com.faultlab.order.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faultlab.order.inventory.DownstreamHttpException;
import com.faultlab.order.inventory.DownstreamRejectedException;
import com.faultlab.order.resilience.ResilienceProperties.BackoffStrategy;
import com.faultlab.order.resilience.ResilienceProperties.Jitter;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import com.faultlab.order.support.TestResilience;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManualRetryExecutorTest {

    private final List<Duration> sleeps = new ArrayList<>();

    private ManualRetryExecutor executor(BackoffStrategy strategy) {
        var cfg = TestResilience.retry(RetryMode.MANUAL, 4, strategy, Jitter.NONE);
        return new ManualRetryExecutor(cfg, new RetryPolicy(cfg), new BackoffCalculator(cfg), sleeps::add);
    }

    @Test
    void retriesUntilSuccessWithLinearBackoff() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor(BackoffStrategy.LINEAR).execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new DownstreamHttpException(503, "");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20));
    }

    @Test
    void exponentialBackoffBetweenAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(BackoffStrategy.EXPONENTIAL).execute(() -> {
            calls.incrementAndGet();
            throw new DownstreamHttpException(503, "");
        })).isInstanceOf(DownstreamHttpException.class);
        assertThat(calls).hasValue(4);
        assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(40));
    }

    @Test
    void doesNotRetryNonRetryableFailures() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor(BackoffStrategy.FIXED).execute(() -> {
            calls.incrementAndGet();
            throw new DownstreamRejectedException(409, "");
        })).isInstanceOf(DownstreamRejectedException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }
}
