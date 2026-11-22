package com.faultlab.order.api;

import com.faultlab.order.api.ApiModels.CreateOrderRequest;
import com.faultlab.order.api.ApiModels.OrderResponse;
import com.faultlab.order.order.OrderService;
import com.faultlab.order.order.PlaceOrderResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String ATTEMPTS_HEADER = "X-Downstream-Attempts";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orders;

    public OrderController(OrderService orders) { this.orders = orders; }

    /**
     * Status mapping:
     * 201 CONFIRMED (new) | 200 CONFIRMED (idempotent replay) | 409 REJECTED by inventory |
     * 202 RESERVATION_UNKNOWN (accepted, being reconciled) | 503 FAILED (inventory not called: circuit open /
     * bulkhead full; retry with the same Idempotency-Key) | 400 invalid | 422 key reused with other payload.
     * Without an Idempotency-Key header a random key is generated (no client-side deduplication).
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        PlaceOrderResult result = orders.place(key, request.productId(), request.quantity());

        HttpStatus status = switch (result.order().status()) {
            case CONFIRMED -> result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
            case REJECTED -> HttpStatus.CONFLICT;
            case RESERVATION_UNKNOWN, PENDING -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status)
                .location(URI.create("/api/orders/" + result.order().orderId()))
                .header(ATTEMPTS_HEADER, String.valueOf(result.downstreamAttempts()))
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable UUID orderId) {
        return OrderResponse.from(orders.get(orderId));
    }
}
