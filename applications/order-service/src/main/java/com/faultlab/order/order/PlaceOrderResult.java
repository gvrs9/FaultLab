package com.faultlab.order.order;

/**
 * @param replayed         true when an existing order for the same idempotency key was returned
 * @param downstreamAttempts HTTP attempts made to inventory while handling THIS request
 */
public record PlaceOrderResult(Order order, boolean replayed, int downstreamAttempts) { }
