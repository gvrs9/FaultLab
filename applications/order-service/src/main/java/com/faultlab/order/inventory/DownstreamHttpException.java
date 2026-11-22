package com.faultlab.order.inventory;

/** Inventory answered with 5xx or 429: an infrastructure-level failure (retryable if configured). */
public class DownstreamHttpException extends RuntimeException {

    private final int statusCode;

    public DownstreamHttpException(int statusCode, String body) {
        super("Inventory responded " + statusCode + (body == null || body.isBlank() ? "" : ": " + body));
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
