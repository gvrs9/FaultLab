package com.faultlab.inventory.api;

import com.faultlab.inventory.failure.SimulatedFailureException;
import com.faultlab.inventory.inventory.InventoryExceptions.IdempotencyMismatchException;
import com.faultlab.inventory.inventory.InventoryExceptions.InsufficientStockException;
import com.faultlab.inventory.inventory.InventoryExceptions.ProductNotFoundException;
import com.faultlab.inventory.inventory.InventoryExceptions.ReservationNotFoundException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.validation.ConstraintViolationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Standard Spring MVC errors (400/404/405/415...) are handled by the superclass as ProblemDetail. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SimulatedFailureException.class)
    ProblemDetail simulated(SimulatedFailureException e) {
        log.warn("Injected failure: {}", e.getMessage());
        markSpan(e);
        return problem(HttpStatus.valueOf(e.status()), e.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail notFound(ProductNotFoundException e) { return problem(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(ReservationNotFoundException.class)
    ProblemDetail reservationNotFound(ReservationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficient(InsufficientStockException e) {
        log.info("Rejected: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    ProblemDetail mismatch(IdempotencyMismatchException e) {
        log.warn("Rejected: {}", e.getMessage());
        return problem(HttpStatus.valueOf(422), e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraint(ConstraintViolationException e) { return problem(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, QueryTimeoutException.class,
            TransientDataAccessException.class})
    ProblemDetail dbUnavailable(DataAccessException e) {
        log.error("Database temporarily unavailable", e);
        markSpan(e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database temporarily unavailable");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dbError(DataAccessException e) {
        log.error("Database error", e);
        markSpan(e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Database error");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        markSpan(e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    private static void markSpan(Throwable e) {
        Span span = Span.current();
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
    }
}
