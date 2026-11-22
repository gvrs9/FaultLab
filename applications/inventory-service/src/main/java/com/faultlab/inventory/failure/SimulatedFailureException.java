package com.faultlab.inventory.failure;

/** A deliberately injected failure; mapped to its HTTP status by the exception handler. */
public class SimulatedFailureException extends RuntimeException {

    private final int status;

    public SimulatedFailureException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
