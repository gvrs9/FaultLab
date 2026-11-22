package com.faultlab.order.inventory;

/**
 * Result of the (possibly retried) reservation call.
 *
 * @param attempts number of HTTP requests actually sent to inventory-service
 */
public record ReservationOutcome(Result result, int attempts, String detail) {

    public enum Result {
        /** Inventory confirmed the reservation (new or idempotent replay). */
        RESERVED,
        /** Inventory gave a definitive negative answer (insufficient stock, unknown product...). */
        REJECTED,
        /** No request was sent (circuit open / bulkhead full): definitely not processed. */
        NOT_SENT,
        /** At least one request was sent but no definitive answer was received: MAY have been processed. */
        UNKNOWN
    }
}
