package com.faultlab.order.inventory;

import java.util.UUID;

public record ReservationResponse(UUID reservationId, UUID orderId, String productId, Integer quantity,
                                  String status, Boolean replayed) { }
