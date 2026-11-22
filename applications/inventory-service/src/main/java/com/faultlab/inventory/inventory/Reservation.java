package com.faultlab.inventory.inventory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Reservation(UUID reservationId, String idempotencyKey, UUID orderId, String productId,
                          int quantity, String status, OffsetDateTime createdAt) {

    boolean matches(UUID otherOrderId, String otherProductId, int otherQuantity) {
        return orderId.equals(otherOrderId) && productId.equals(otherProductId) && quantity == otherQuantity;
    }
}
