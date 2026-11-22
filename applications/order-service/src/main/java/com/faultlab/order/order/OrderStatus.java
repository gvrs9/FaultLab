package com.faultlab.order.order;

public enum OrderStatus {
    /** Persisted, reservation not yet attempted/finished. */
    PENDING,
    /** Stock reserved. */
    CONFIRMED,
    /** Inventory gave a definitive negative answer. */
    REJECTED,
    /** Inventory was never called (circuit open / bulkhead full): safe to retry later. */
    FAILED,
    /** A request was sent but the result is unknown (timeout, 5xx). Resolved by the reconciler. */
    RESERVATION_UNKNOWN;

    public boolean isFinal() { return this == CONFIRMED || this == REJECTED; }
}
