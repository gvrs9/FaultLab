package com.faultlab.order.resilience;

import com.faultlab.order.resilience.ResilienceProperties.Retry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Computes the wait before retry number {@code n} (n = 1 is the first retry).
 * <ul>
 *   <li>FIXED:       initial</li>
 *   <li>LINEAR:      initial * n</li>
 *   <li>EXPONENTIAL: initial * multiplier^(n-1)</li>
 * </ul>
 * The result is capped at maxInterval, then jitter is applied:
 * FULL = random(0, d); EQUAL = d/2 + random(0, d/2).
 */
public final class BackoffCalculator {

    private final Retry config;
    private final DoubleSupplier random;

    public BackoffCalculator(Retry config) {
        this(config, () -> ThreadLocalRandom.current().nextDouble());
    }

    BackoffCalculator(Retry config, DoubleSupplier random) {
        this.config = config;
        this.random = random;
    }

    public Duration delayBeforeRetry(int retryNumber) {
        int n = Math.max(1, retryNumber);
        double initial = config.initialInterval().toMillis();
        double raw = switch (config.backoffStrategy()) {
            case FIXED -> initial;
            case LINEAR -> initial * n;
            case EXPONENTIAL -> initial * Math.pow(config.multiplier(), n - 1);
        };
        long capped = (long) Math.min(raw, config.maxInterval().toMillis());
        long jittered = switch (config.jitter()) {
            case NONE -> capped;
            case FULL -> (long) (random.getAsDouble() * capped);
            case EQUAL -> capped / 2 + (long) (random.getAsDouble() * (capped - capped / 2));
        };
        return Duration.ofMillis(Math.max(0, jittered));
    }
}
