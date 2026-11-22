package com.faultlab.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultlab.reconciler")
public record ReconcilerProperties(boolean enabled, long intervalMs, Duration minAge, int batchSize) {
    public ReconcilerProperties {
        minAge = minAge == null || minAge.isNegative() ? Duration.ofSeconds(30) : minAge;
        batchSize = Math.max(1, Math.min(500, batchSize));
    }
}
