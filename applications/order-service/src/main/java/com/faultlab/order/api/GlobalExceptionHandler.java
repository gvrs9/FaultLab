package com.faultlab.order.api;

import com.faultlab.order.order.OrderExceptions.IdempotencyConflictException;
import com.faultlab.order.order.OrderExceptions.OrderNotFoundException;
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

/** Standard Spring MVC errors (400/404/405/415, body/param validation) are handled by the superclass. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) { return problem(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
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
