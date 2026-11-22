package com.faultlab.order.order;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Order(UUID orderId, String idempotencyKey, String productId, int quantity, OrderStatus status,
                    String failureReason, int downstreamAttempts, OffsetDateTime createdAt,
                    OffsetDateTime updatedAt) {

    public boolean sameRequest(String otherProductId, int otherQuantity) {
        return productId.equals(otherProductId) && quantity == otherQuantity;
    }
}
