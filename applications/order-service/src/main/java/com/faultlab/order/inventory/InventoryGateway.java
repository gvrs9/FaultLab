package com.faultlab.order.inventory;

import com.faultlab.order.inventory.ReservationOutcome.Result;
import com.faultlab.order.resilience.ManualRetryExecutor;
import com.faultlab.order.resilience.ResilienceProperties;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.stereotype.Component;

/**
 * Applies the configured protections around a single inventory call:
 * <pre>  Retry( CircuitBreaker( Bulkhead( HTTP attempt ) ) )  </pre>
 * Retry is either the hand-written loop (MANUAL) or Resilience4j (RESILIENCE4J), or disabled (NONE).
 * Every failure is translated into a {@link ReservationOutcome}; nothing is thrown to callers.
 */
@Component
public class InventoryGateway {

    private static final Logger log = LogManager.getLogger(InventoryGateway.class);

    private final InventoryClient client;
    private final ResilienceProperties props;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final ManualRetryExecutor manualRetry;
    private final MeterRegistry meters;

    public InventoryGateway(InventoryClient client, ResilienceProperties props, CircuitBreaker inventoryCircuitBreaker,
                            Retry inventoryRetry, Bulkhead inventoryBulkhead, ManualRetryExecutor manualRetry,
                            MeterRegistry meters) {
        this.client = client;
        this.props = props;
        this.circuitBreaker = inventoryCircuitBreaker;
        this.retry = inventoryRetry;
        this.bulkhead = inventoryBulkhead;
        this.manualRetry = manualRetry;
        this.meters = meters;
    }

    public ReservationOutcome reserve(ReservationRequest request) {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<ReservationResponse> call = () -> attempt(request, attempts);
        if (props.bulkhead().enabled()) {
            call = Bulkhead.decorateSupplier(bulkhead, call);
        }
        if (props.circuitBreaker().enabled()) {
            call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        }
        RetryMode mode = props.retry().effectiveMode();
        try {
            ResponseHolder holder = switch (mode) {
                case NONE -> new ResponseHolder(call.get());
                case MANUAL -> new ResponseHolder(manualRetry.execute(call));
                case RESILIENCE4J -> new ResponseHolder(Retry.decorateSupplier(retry, call).get());
            };
            return outcome(Result.RESERVED, attempts.get(), holder.response() != null
                    && Boolean.TRUE.equals(holder.response().replayed()) ? "idempotent replay" : null, mode);
        } catch (DownstreamRejectedException e) {
            return outcome(Result.REJECTED, attempts.get(), e.getMessage(), mode);
        } catch (CallNotPermittedException e) {
            return unavailable(attempts.get(), "circuit breaker open", mode);
        } catch (BulkheadFullException e) {
            return unavailable(attempts.get(), "bulkhead full", mode);
        } catch (RuntimeException e) {
            // Timeouts, connection errors, 5xx after retries: the request may or may not have been processed.
            log.warn("Reservation outcome unknown for order {} after {} attempt(s): {}",
                    request.orderId(), attempts.get(), e.getMessage());
            return outcome(Result.UNKNOWN, attempts.get(), abbreviate(e.getMessage()), mode);
        } finally {
            ThreadContext.remove("retry_attempt");
        }
    }

    private ReservationResponse attempt(ReservationRequest request, AtomicInteger attempts) {
        int n = attempts.incrementAndGet();
        ThreadContext.put("retry_attempt", String.valueOf(n));
        try {
            ReservationResponse response = client.reserve(request);
            attemptCounter("success").increment();
            return response;
        } catch (DownstreamRejectedException e) {
            attemptCounter("rejected").increment();
            throw e;
        } catch (DownstreamHttpException e) {
            attemptCounter("http_error").increment();
            log.warn("Inventory attempt {} for order {} failed with HTTP {}", n, request.orderId(), e.statusCode());
            throw e;
        } catch (RuntimeException e) {
            attemptCounter("io_error").increment();
            log.warn("Inventory attempt {} for order {} failed: {}", n, request.orderId(), e.getMessage());
            throw e;
        }
    }

    private ReservationOutcome unavailable(int attempts, String reason, RetryMode mode) {
        // If an earlier attempt was sent before the breaker opened, its effect is unknown.
        Result result = attempts == 0 ? Result.NOT_SENT : Result.UNKNOWN;
        log.warn("Inventory call not executed: {} ({} prior attempt(s))", reason, attempts);
        return outcome(result, attempts, reason, mode);
    }

    private ReservationOutcome outcome(Result result, int attempts, String detail, RetryMode mode) {
        Counter.builder("faultlab.downstream.calls")
                .description("Logical reservation calls (after retries)")
                .tag("outcome", result.name().toLowerCase())
                .tag("retried", String.valueOf(attempts > 1))
                .tag("retry_mode", mode.name())
                .register(meters).increment();
        return new ReservationOutcome(result, attempts, detail);
    }

    private Counter attemptCounter(String outcome) {
        return Counter.builder("faultlab.downstream.attempts")
                .description("Physical HTTP attempts sent to inventory-service")
                .tag("outcome", outcome)
                .register(meters);
    }

    private static String abbreviate(String s) {
        return s == null ? null : (s.length() > 400 ? s.substring(0, 400) : s);
    }

    private record ResponseHolder(ReservationResponse response) { }
}
