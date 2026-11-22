package com.faultlab.inventory.db;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param artificialDelay initial artificial delay (pg_sleep) executed inside every DB operation */
@ConfigurationProperties(prefix = "faultlab.db")
public record DatabaseProperties(Duration artificialDelay) {
    public DatabaseProperties {
        if (artificialDelay == null || artificialDelay.isNegative()) {
            artificialDelay = Duration.ZERO;
        }
    }
}
