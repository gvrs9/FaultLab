package com.faultlab.inventory.inventory;

public final class InventoryExceptions {

    private InventoryExceptions() { }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String productId) { super("Unknown product " + productId); }
    }

    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String productId, int requested) {
            super("Insufficient stock for " + productId + " (requested " + requested + ")");
        }
    }

    public static class IdempotencyMismatchException extends RuntimeException {
        public IdempotencyMismatchException(String key) {
            super("Idempotency key " + key + " was already used with a different request payload");
        }
    }

    public static class ReservationNotFoundException extends RuntimeException {
        public ReservationNotFoundException(String key) { super("No reservation for key " + key); }
    }
}
