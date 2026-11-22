package com.faultlab.order.resilience;

import com.faultlab.order.inventory.DownstreamHttpException;
import com.faultlab.order.resilience.ResilienceProperties.Retry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Decides what is safe and useful to retry. Only configured HTTP status codes and configured exception
 * types (matched anywhere in the cause chain, including subclasses) are retried. Everything else,
 * notably 4xx business answers, circuit-breaker and bulkhead rejections, is not retried.
 *
 * Retrying a timed-out request is only acceptable because every reservation carries an idempotency key:
 * a timeout does NOT mean the request was not processed.
 */
public final class RetryPolicy {

    private static final Logger log = LogManager.getLogger(RetryPolicy.class);

    private final Set<Integer> statusCodes;
    private final List<Class<?>> exceptionTypes;

    public RetryPolicy(Retry config) {
        this.statusCodes = Set.copyOf(config.retryableStatusCodes());
        List<Class<?>> types = new ArrayList<>();
        for (String name : config.retryableExceptions()) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                types.add(Class.forName(trimmed));
            } catch (ClassNotFoundException e) {
                log.warn("Ignoring unknown retryable exception class '{}'", trimmed);
            }
        }
        this.exceptionTypes = List.copyOf(types);
    }

    public boolean isRetryable(Throwable failure) {
        if (failure instanceof DownstreamHttpException http) {
            return statusCodes.contains(http.statusCode());
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            for (Class<?> type : exceptionTypes) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
