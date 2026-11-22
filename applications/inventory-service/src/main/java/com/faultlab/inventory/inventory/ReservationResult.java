package com.faultlab.inventory.inventory;

/** @param replayed true when an earlier reservation with the same idempotency key was returned */
public record ReservationResult(Reservation reservation, boolean replayed) { }
