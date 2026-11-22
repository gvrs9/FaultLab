package com.faultlab.inventory.failure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Thread-safe failure injection. Settings are swapped atomically; per-key bookkeeping uses concurrent
 * collections and is bounded so a long load test cannot grow memory without limit.
 */
@Component
public class FailureSimulator {

    private static final Logger log = LogManager.getLogger(FailureSimulator.class);
    static final int MAX_TRACKED_KEYS = 200_000;

    private final FailureSettings defaults;
    private final AtomicReference<FailureSettings> settings;
    private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();
    private final Set<String> failedAfterProcessing = ConcurrentHashMap.newKeySet();
    private final MeterRegistry registry;

    public FailureSimulator(FailureProperties props, MeterRegistry registry) {
        this.defaults = props.toSettings();
        this.settings = new AtomicReference<>(defaults);
        this.registry = registry;
        for (FailureMode mode : FailureMode.values()) {
            Gauge.builder("faultlab.failure.mode", settings, s -> s.get().mode() == mode ? 1 : 0)
                    .tag("mode", mode.name())
                    .description("1 if this failure mode is active")
                    .register(registry);
        }
    }

    public FailureSettings current() { return settings.get(); }

    public FailureSettings update(FailureSettings newSettings) {
        attemptsByKey.clear();
        failedAfterProcessing.clear();
        settings.set(newSettings);
        log.warn("Failure mode changed to {}", newSettings);
        return newSettings;
    }

    public FailureSettings reset() {
        return update(new FailureSettings(FailureMode.NORMAL, defaults.slowLatencyMs(), defaults.failureRate(), 0));
    }

    /** Called before the reservation is processed. May sleep or throw. */
    public void beforeProcessing(String idempotencyKey) {
        FailureSettings s = settings.get();
        sleep(s.baseLatencyMs());
        switch (s.mode()) {
            case NORMAL, PROCESS_THEN_FAIL_ONCE -> { }
            case FAIL_FIRST_2 -> {
                int attempt = track(attemptsByKey).computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
                if (attempt <= 2) {
                    throw inject(503, "FAIL_FIRST_2: attempt " + attempt + " rejected before processing");
                }
            }
            case ALWAYS_FAIL -> throw inject(503, "ALWAYS_FAIL: rejected before processing");
            case SLOW_5_SECONDS -> {
                registry.counter("faultlab.failure.injected", "type", "slow").increment();
                sleep(s.slowLatencyMs());
            }
            case RANDOM_FAILURE -> {
                if (ThreadLocalRandom.current().nextDouble() < s.failureRate()) {
                    throw inject(503, "RANDOM_FAILURE: rejected before processing");
                }
            }
        }
    }

    /** Called after the reservation was committed. May throw to simulate a lost response. */
    public void afterProcessing(String idempotencyKey) {
        if (settings.get().mode() == FailureMode.PROCESS_THEN_FAIL_ONCE
                && track(failedAfterProcessing).add(idempotencyKey)) {
            throw inject(500, "PROCESS_THEN_FAIL_ONCE: reservation committed but response failed");
        }
    }

    private SimulatedFailureException inject(int status, String message) {
        registry.counter("faultlab.failure.injected", "type", status == 500 ? "after_processing" : "before_processing")
                .increment();
        return new SimulatedFailureException(status, message);
    }

    private <C extends java.util.Collection<?>> C track(C collection) {
        if (collection.size() > MAX_TRACKED_KEYS) {
            collection.clear();
        }
        return collection;
    }

    private <M extends Map<?, ?>> M track(M map) {
        if (map.size() > MAX_TRACKED_KEYS) {
            map.clear();
        }
        return map;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
