package com.faultlab.order.inventory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultlab.inventory")
public record InventoryClientProperties(String baseUrl, Duration connectTimeout, Duration responseTimeout) {

    public InventoryClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("faultlab.inventory.base-url (INVENTORY_BASE_URL) must be set");
        }
        connectTimeout = connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                ? Duration.ofMillis(500) : connectTimeout;
        responseTimeout = responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()
                ? Duration.ofSeconds(2) : responseTimeout;
    }
}
