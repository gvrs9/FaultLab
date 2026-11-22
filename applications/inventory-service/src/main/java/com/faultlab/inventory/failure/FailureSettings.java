package com.faultlab.inventory.failure;

/**
 * Immutable, validated failure-simulation settings.
 *
 * @param mode           active failure mode
 * @param slowLatencyMs  delay used by SLOW_5_SECONDS
 * @param failureRate    probability [0..1] used by RANDOM_FAILURE
 * @param baseLatencyMs  extra latency added to every reservation in every mode
 */
public record FailureSettings(FailureMode mode, long slowLatencyMs, double failureRate, long baseLatencyMs) {

    public static final long MAX_LATENCY_MS = 60_000;

    public FailureSettings {
        if (mode == null) {
            mode = FailureMode.NORMAL;
        }
        slowLatencyMs = Math.max(0, Math.min(MAX_LATENCY_MS, slowLatencyMs));
        baseLatencyMs = Math.max(0, Math.min(MAX_LATENCY_MS, baseLatencyMs));
        failureRate = Double.isNaN(failureRate) ? 0 : Math.max(0, Math.min(1, failureRate));
    }
}
