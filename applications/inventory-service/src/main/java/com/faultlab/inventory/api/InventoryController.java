package com.faultlab.inventory.api;

import com.faultlab.inventory.api.ApiModels.ReservationRequest;
import com.faultlab.inventory.api.ApiModels.ReservationResponse;
import com.faultlab.inventory.api.ApiModels.StockResponse;
import com.faultlab.inventory.failure.FailureSimulator;
import com.faultlab.inventory.inventory.Product;
import com.faultlab.inventory.inventory.Reservation;
import com.faultlab.inventory.inventory.ReservationResult;
import com.faultlab.inventory.inventory.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/inventory")
public class InventoryController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ReservationService service;
    private final FailureSimulator failures;

    public InventoryController(ReservationService service, FailureSimulator failures) {
        this.service = service;
        this.failures = failures;
    }

    /**
     * 201 = new reservation, 200 = idempotent replay, 404 unknown product, 409 insufficient stock,
     * 422 key reused with a different payload, 500/503 = injected failures.
     */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @Valid @RequestBody ReservationRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) @Size(max = 100) String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? request.orderId().toString() : idempotencyKey;

        failures.beforeProcessing(key);
        ReservationResult result = service.reserve(key, request.orderId(), request.productId(), request.quantity());
        failures.afterProcessing(key);   // the transaction is already committed at this point

        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.reservation(), result.replayed()));
    }

    @GetMapping("/reservations/{idempotencyKey}")
    public ReservationResponse reservation(@PathVariable @Size(max = 100) String idempotencyKey) {
        return toResponse(service.reservation(idempotencyKey), false);
    }

    @GetMapping("/{productId}")
    public StockResponse stock(@PathVariable @Size(max = 64) String productId) {
        Product p = service.product(productId);
        return new StockResponse(p.productId(), p.available(), p.reserved());
    }

    private static ReservationResponse toResponse(Reservation r, boolean replayed) {
        return new ReservationResponse(r.reservationId(), r.orderId(), r.productId(), r.quantity(), r.status(),
                replayed, r.createdAt());
    }
}
