package com.faultlab.order.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.faultlab.order.resilience.ResilienceProperties.BackoffStrategy;
import com.faultlab.order.resilience.ResilienceProperties.Jitter;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class BackoffCalculatorTest {

    private static ResilienceProperties.Retry cfg(BackoffStrategy s, Jitter j, long initialMs, double mult, long maxMs) {
        return new ResilienceProperties.Retry(true, RetryMode.MANUAL, 5, List.of(), List.of(), s,
                Duration.ofMillis(initialMs), mult, Duration.ofMillis(maxMs), j);
    }

    @Test
    void fixedBackoffIsConstant() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.FIXED, Jitter.NONE, 100, 2, 10_000));
        assertThat(calc.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(calc.delayBeforeRetry(4)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void linearBackoffGrowsLinearly() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.LINEAR, Jitter.NONE, 100, 2, 10_000));
        assertThat(calc.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(calc.delayBeforeRetry(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(calc.delayBeforeRetry(3)).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void exponentialBackoffGrowsAndIsCapped() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.EXPONENTIAL, Jitter.NONE, 100, 2, 500));
        assertThat(calc.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(calc.delayBeforeRetry(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(calc.delayBeforeRetry(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(calc.delayBeforeRetry(4)).isEqualTo(Duration.ofMillis(500));
        assertThat(calc.delayBeforeRetry(60)).isEqualTo(Duration.ofMillis(500));   // no overflow
    }

    @Test
    void fullJitterUsesRandomFraction() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.FIXED, Jitter.FULL, 1000, 2, 10_000), () -> 0.25);
        assertThat(calc.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void equalJitterKeepsHalf() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.FIXED, Jitter.EQUAL, 1000, 2, 10_000), () -> 0.0);
        assertThat(calc.delayBeforeRetry(1)).isEqualTo(Duration.ofMillis(500));
    }

    @RepeatedTest(50)
    void jitterStaysWithinBounds() {
        var calc = new BackoffCalculator(cfg(BackoffStrategy.EXPONENTIAL, Jitter.FULL, 100, 2, 1000));
        assertThat(calc.delayBeforeRetry(3).toMillis()).isBetween(0L, 400L);
    }
}
