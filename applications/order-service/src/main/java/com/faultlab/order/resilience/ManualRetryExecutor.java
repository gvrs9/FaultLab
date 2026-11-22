package com.faultlab.order.resilience;

import com.faultlab.order.resilience.ResilienceProperties.Retry;
import java.time.Duration;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The hand-written retry loop (the "before Resilience4j" stage of the lab). Uses the same
 * {@link RetryPolicy} and {@link BackoffCalculator} as the Resilience4j configuration so both modes
 * can be compared like-for-like.
 */
public final class ManualRetryExecutor {

    private static final Logger log = LogManager.getLogger(ManualRetryExecutor.class);

    @FunctionalInterface
    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    private final int maxAttempts;
    private final RetryPolicy policy;
    private final BackoffCalculator backoff;
    private final Sleeper sleeper;

    public ManualRetryExecutor(Retry config, RetryPolicy policy, BackoffCalculator backoff) {
        this(config, policy, backoff, d -> Thread.sleep(d.toMillis()));
    }

    public ManualRetryExecutor(Retry config, RetryPolicy policy, BackoffCalculator backoff, Sleeper sleeper) {
        this.maxAttempts = config.maxAttempts();
        this.policy = policy;
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    public <T> T execute(Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !policy.isRetryable(e)) {
                    throw e;
                }
                Duration wait = backoff.delayBeforeRetry(attempt);
                log.info("Retrying downstream call in {} ms (attempt {} of {} failed: {})",
                        wait.toMillis(), attempt, maxAttempts, e.getClass().getSimpleName());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
