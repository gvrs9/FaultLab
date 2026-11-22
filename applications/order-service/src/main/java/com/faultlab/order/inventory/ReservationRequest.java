package com.faultlab.order.inventory;

import java.util.UUID;

public record ReservationRequest(UUID orderId, String productId, int quantity) { }
