package com.faultlab.order.order;

import java.util.UUID;

public final class OrderExceptions {

    private OrderExceptions() { }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) { super("Order " + id + " not found"); }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("Idempotency-Key " + key + " was already used with a different request payload");
        }
    }
}
