package com.faultlab.order.inventory;

/** Inventory gave a definitive 4xx business answer (e.g. 409 insufficient stock). Never retried. */
public class DownstreamRejectedException extends RuntimeException {

    private final int statusCode;

    public DownstreamRejectedException(int statusCode, String body) {
        super("Inventory rejected the reservation with " + statusCode + (body == null || body.isBlank() ? "" : ": " + body));
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
