package com.faultlab.inventory.db;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every database operation in one transaction (one pooled connection), optionally preceded by
 * an artificial {@code pg_sleep} so the connection is held longer: the basis of the HikariCP experiments.
 * Records per-operation latency and categorised error counts (low-cardinality tags only).
 */
@Component
public class DbOperations {

    private static final long MAX_DELAY_MS = 60_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;
    private final AtomicLong delayMs;

    public DbOperations(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry registry, DatabaseProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.registry = registry;
        this.delayMs = new AtomicLong(clamp(props.artificialDelay().toMillis()));
        Gauge.builder("faultlab.db.artificial.delay", delayMs, AtomicLong::get)
                .baseUnit("milliseconds")
                .description("Artificial delay executed inside each DB transaction")
                .register(registry);
    }

    public <T> T execute(String operation, Function<JdbcClient, T> work) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return tx.execute(status -> {
                long delay = delayMs.get();
                if (delay > 0) {
                    jdbc.sql("SELECT 1 FROM pg_sleep(:seconds)").param("seconds", delay / 1000.0)
                            .query(Integer.class).single();
                }
                return work.apply(jdbc);
            });
        } catch (CannotGetJdbcConnectionException e) {
            outcome = "error";
            errorCounter(operation, "connection_timeout").increment();
            throw e;
        } catch (QueryTimeoutException e) {
            outcome = "error";
            errorCounter(operation, "query_timeout").increment();
            throw e;
        } catch (DataAccessException e) {
            outcome = "error";
            errorCounter(operation, "data_access").increment();
            throw e;
        } catch (RuntimeException e) {
            outcome = "business_error";   // e.g. domain exceptions that roll the transaction back
            throw e;
        } finally {
            sample.stop(Timer.builder("faultlab.db.operation")
                    .description("Latency of a database operation incl. connection acquisition")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    public long artificialDelayMs() { return delayMs.get(); }

    public long setArtificialDelay(Duration delay) {
        long value = clamp(delay == null ? 0 : delay.toMillis());
        delayMs.set(value);
        return value;
    }

    private Counter errorCounter(String operation, String type) {
        return Counter.builder("faultlab.db.errors").tag("operation", operation).tag("type", type).register(registry);
    }

    private static long clamp(long ms) { return Math.max(0, Math.min(MAX_DELAY_MS, ms)); }
}
