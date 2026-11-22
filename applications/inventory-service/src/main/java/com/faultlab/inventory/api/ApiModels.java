package com.faultlab.inventory.api;

import com.faultlab.inventory.failure.FailureMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    public record ReservationRequest(
            @NotNull UUID orderId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String productId,
            @NotNull @Min(1) @Max(1000) Integer quantity) { }

    public record ReservationResponse(UUID reservationId, UUID orderId, String productId, int quantity,
                                      String status, boolean replayed, OffsetDateTime createdAt) { }

    public record StockResponse(String productId, int available, int reserved) { }

    /** Null fields keep the current value. */
    public record FailureModeRequest(
            @NotNull FailureMode mode,
            @Min(0) @Max(60_000) Long slowLatencyMs,
            @DecimalMin("0.0") @DecimalMax("1.0") Double failureRate,
            @Min(0) @Max(60_000) Long baseLatencyMs,
            @Min(0) @Max(60_000) Long dbDelayMs) { }

    public record FailureModeResponse(FailureMode mode, long slowLatencyMs, double failureRate,
                                      long baseLatencyMs, long dbDelayMs) { }
}
