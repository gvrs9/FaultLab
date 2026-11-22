package com.faultlab.inventory.failure;

public enum FailureMode {
    /** Process every request normally. */
    NORMAL,
    /** Fail the first two attempts for each idempotency key with 503 (before processing). */
    FAIL_FIRST_2,
    /** Fail every reservation with 503 (before processing). */
    ALWAYS_FAIL,
    /** Delay every reservation by the slow latency (default 5 s), then process it. */
    SLOW_5_SECONDS,
    /** Process and commit the reservation, then return 500 once per idempotency key. */
    PROCESS_THEN_FAIL_ONCE,
    /** Fail with 503 (before processing) with the configured probability. */
    RANDOM_FAILURE
}
