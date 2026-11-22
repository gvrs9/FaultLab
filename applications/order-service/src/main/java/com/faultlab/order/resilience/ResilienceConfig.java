package com.faultlab.order.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    private static final Logger log = LogManager.getLogger(ResilienceConfig.class);

    @Bean
    RetryPolicy retryPolicy(ResilienceProperties props) { return new RetryPolicy(props.retry()); }

    @Bean
    BackoffCalculator backoffCalculator(ResilienceProperties props) { return new BackoffCalculator(props.retry()); }

    @Bean
    ManualRetryExecutor manualRetryExecutor(ResilienceProperties props, RetryPolicy policy, BackoffCalculator backoff) {
        return new ManualRetryExecutor(props.retry(), policy, backoff);
    }

    @Bean
    CircuitBreaker inventoryCircuitBreaker(ResilienceProperties props, MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(ResilienceFactory.circuitBreakerConfig(props.circuitBreaker()));
        CircuitBreaker cb = registry.circuitBreaker(ResilienceFactory.INVENTORY);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        cb.getEventPublisher().onStateTransition(e ->
                log.warn("Circuit breaker '{}' transition {}", e.getCircuitBreakerName(), e.getStateTransition()));
        return cb;
    }

    @Bean
    Retry inventoryRetry(ResilienceProperties props, RetryPolicy policy, BackoffCalculator backoff, MeterRegistry meters) {
        RetryRegistry registry = RetryRegistry.of(ResilienceFactory.retryConfig(props.retry(), policy, backoff));
        Retry retry = registry.retry(ResilienceFactory.INVENTORY);
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meters);
        retry.getEventPublisher().onRetry(e -> log.info("Resilience4j retry #{} in {} ms after {}",
                e.getNumberOfRetryAttempts(), e.getWaitInterval().toMillis(),
                e.getLastThrowable() == null ? "-" : e.getLastThrowable().getClass().getSimpleName()));
        return retry;
    }

    @Bean
    Bulkhead inventoryBulkhead(ResilienceProperties props, MeterRegistry meters) {
        BulkheadRegistry registry = BulkheadRegistry.of(ResilienceFactory.bulkheadConfig(props.bulkhead()));
        Bulkhead bulkhead = registry.bulkhead(ResilienceFactory.INVENTORY);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meters);
        return bulkhead;
    }
}
