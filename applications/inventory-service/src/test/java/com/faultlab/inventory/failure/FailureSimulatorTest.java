package com.faultlab.inventory.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FailureSimulatorTest {

    private final FailureSimulator sim = new FailureSimulator(
            new FailureProperties(FailureMode.NORMAL, 5000, 0.3, 0), new SimpleMeterRegistry());

    private void mode(FailureMode mode, long slowMs, double rate) {
        sim.update(new FailureSettings(mode, slowMs, rate, 0));
    }

    @Test
    void normalNeverFails() {
        assertThatCode(() -> { sim.beforeProcessing("k"); sim.afterProcessing("k"); }).doesNotThrowAnyException();
    }

    @Test
    void failFirstTwoIsPerKey() {
        mode(FailureMode.FAIL_FIRST_2, 0, 0);
        assertThatThrownBy(() -> sim.beforeProcessing("a")).isInstanceOf(SimulatedFailureException.class);
        assertThatThrownBy(() -> sim.beforeProcessing("a")).isInstanceOf(SimulatedFailureException.class);
        assertThatCode(() -> sim.beforeProcessing("a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> sim.beforeProcessing("b")).isInstanceOf(SimulatedFailureException.class);
    }

    @Test
    void failFirstTwoIsThreadSafe() throws Exception {
        mode(FailureMode.FAIL_FIRST_2, 0, 0);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    sim.beforeProcessing("same-key");
                } catch (SimulatedFailureException e) {
                    failures.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(2);
    }

    @Test
    void alwaysFailReturns503() {
        mode(FailureMode.ALWAYS_FAIL, 0, 0);
        assertThatThrownBy(() -> sim.beforeProcessing("k"))
                .isInstanceOfSatisfying(SimulatedFailureException.class, e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test
    void processThenFailOnceFailsAfterProcessingOncePerKey() {
        mode(FailureMode.PROCESS_THEN_FAIL_ONCE, 0, 0);
        assertThatCode(() -> sim.beforeProcessing("k")).doesNotThrowAnyException();
        assertThatThrownBy(() -> sim.afterProcessing("k"))
                .isInstanceOfSatisfying(SimulatedFailureException.class, e -> assertThat(e.status()).isEqualTo(500));
        assertThatCode(() -> sim.afterProcessing("k")).doesNotThrowAnyException();
    }

    @Test
    void randomFailureHonoursRate() {
        mode(FailureMode.RANDOM_FAILURE, 0, 1.0);
        assertThatThrownBy(() -> sim.beforeProcessing("k")).isInstanceOf(SimulatedFailureException.class);
        mode(FailureMode.RANDOM_FAILURE, 0, 0.0);
        assertThatCode(() -> sim.beforeProcessing("k")).doesNotThrowAnyException();
    }

    @Test
    void slowModeDelays() {
        mode(FailureMode.SLOW_5_SECONDS, 150, 0);
        long start = System.nanoTime();
        sim.beforeProcessing("k");
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(140);
    }

    @Test
    void resetRestoresNormal() {
        mode(FailureMode.ALWAYS_FAIL, 0, 0);
        assertThat(sim.reset().mode()).isEqualTo(FailureMode.NORMAL);
        assertThatCode(() -> sim.beforeProcessing("k")).doesNotThrowAnyException();
    }

    @Test
    void settingsAreClamped() {
        var s = new FailureSettings(null, -1, 7.0, 999_999);
        assertThat(s.mode()).isEqualTo(FailureMode.NORMAL);
        assertThat(s.slowLatencyMs()).isZero();
        assertThat(s.failureRate()).isEqualTo(1.0);
        assertThat(s.baseLatencyMs()).isEqualTo(FailureSettings.MAX_LATENCY_MS);
    }
}
