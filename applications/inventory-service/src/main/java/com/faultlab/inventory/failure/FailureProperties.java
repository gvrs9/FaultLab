package com.faultlab.inventory.failure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultlab.failure")
public record FailureProperties(FailureMode mode, long slowLatencyMs, double failureRate, long baseLatencyMs) {

    public FailureSettings toSettings() {
        return new FailureSettings(mode, slowLatencyMs, failureRate, baseLatencyMs);
    }
}
