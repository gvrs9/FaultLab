package com.faultlab.order.api;

import com.faultlab.order.order.Order;
import com.faultlab.order.order.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class ApiModels {

    private ApiModels() { }

    public record CreateOrderRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) { }

    public record OrderResponse(UUID orderId, String productId, int quantity, OrderStatus status,
                                String failureReason, int downstreamAttempts, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {

        static OrderResponse from(Order o) {
            return new OrderResponse(o.orderId(), o.productId(), o.quantity(), o.status(), o.failureReason(),
                    o.downstreamAttempts(), o.createdAt(), o.updatedAt());
        }
    }

    public record DbDelayRequest(@NotNull @Min(0) @Max(60_000) Long delayMs) { }

    public record DbDelayResponse(long delayMs) { }
}
