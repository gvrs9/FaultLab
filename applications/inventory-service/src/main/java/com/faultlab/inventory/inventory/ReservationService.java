package com.faultlab.inventory.inventory;

import com.faultlab.inventory.db.DbOperations;
import com.faultlab.inventory.inventory.InventoryExceptions.IdempotencyMismatchException;
import com.faultlab.inventory.inventory.InventoryExceptions.InsufficientStockException;
import com.faultlab.inventory.inventory.InventoryExceptions.ProductNotFoundException;
import com.faultlab.inventory.inventory.InventoryExceptions.ReservationNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class ReservationService {

    private static final Logger log = LogManager.getLogger(ReservationService.class);

    private final DbOperations db;
    private final InventoryRepository repository;
    private final MeterRegistry registry;

    public ReservationService(DbOperations db, InventoryRepository repository, MeterRegistry registry) {
        this.db = db;
        this.repository = repository;
        this.registry = registry;
    }

    /**
     * Idempotent reservation in a single transaction:
     * 1. INSERT the reservation keyed by the idempotency key (ON CONFLICT DO NOTHING). Concurrent duplicates
     *    block on the unique index until the first transaction finishes, then see its row.
     * 2. Conditionally decrement stock; if that fails, the exception rolls back the inserted reservation.
     */
    public ReservationResult reserve(String idempotencyKey, UUID orderId, String productId, int quantity) {
        ReservationResult result = db.execute("reserve", jdbc -> {
            boolean inserted = repository.insertReservationIfAbsent(jdbc, UUID.randomUUID(), idempotencyKey,
                    orderId, productId, quantity);
            if (!inserted) {
                Reservation existing = repository.findReservation(jdbc, idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Reservation vanished for " + idempotencyKey));
                if (!existing.matches(orderId, productId, quantity)) {
                    throw new IdempotencyMismatchException(idempotencyKey);
                }
                return new ReservationResult(existing, true);
            }
            if (!repository.decrementStock(jdbc, productId, quantity)) {
                if (repository.findProduct(jdbc, productId).isEmpty()) {
                    throw new ProductNotFoundException(productId);
                }
                throw new InsufficientStockException(productId, quantity);
            }
            return new ReservationResult(repository.findReservation(jdbc, idempotencyKey).orElseThrow(), false);
        });
        registry.counter("faultlab.inventory.reservations", "outcome", result.replayed() ? "replayed" : "reserved")
                .increment();
        if (result.replayed()) {
            log.info("Idempotent replay for key {} (order {})", idempotencyKey, orderId);
        } else {
            log.info("Reserved {} x {} for order {}", quantity, productId, orderId);
        }
        return result;
    }

    public Product product(String productId) {
        return db.execute("get_product", jdbc -> repository.findProduct(jdbc, productId))
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    public Reservation reservation(String idempotencyKey) {
        return db.execute("get_reservation", jdbc -> repository.findReservation(jdbc, idempotencyKey))
                .orElseThrow(() -> new ReservationNotFoundException(idempotencyKey));
    }
}
