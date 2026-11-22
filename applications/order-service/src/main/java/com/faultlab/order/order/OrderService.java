package com.faultlab.order.order;

import com.faultlab.order.db.DbOperations;
import com.faultlab.order.inventory.InventoryGateway;
import com.faultlab.order.inventory.ReservationOutcome;
import com.faultlab.order.inventory.ReservationRequest;
import com.faultlab.order.order.OrderExceptions.IdempotencyConflictException;
import com.faultlab.order.order.OrderExceptions.OrderNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Order placement in three short steps, deliberately NOT one transaction: a DB connection must never be held
 * while waiting on a remote call (that is how latency in B exhausts the pool in A).
 * <ol>
 *   <li>Insert the order (idempotent on the client's Idempotency-Key).</li>
 *   <li>Reserve stock in inventory (idempotent on the order id) with retry / circuit breaker / bulkhead.</li>
 *   <li>Record the outcome.</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final Logger log = LogManager.getLogger(OrderService.class);

    private final DbOperations db;
    private final OrderRepository repository;
    private final InventoryGateway inventory;
    private final MeterRegistry meters;

    public OrderService(DbOperations db, OrderRepository repository, InventoryGateway inventory, MeterRegistry meters) {
        this.db = db;
        this.repository = repository;
        this.inventory = inventory;
        this.meters = meters;
    }

    public PlaceOrderResult place(String idempotencyKey, String productId, int quantity) {
        UUID newId = UUID.randomUUID();
        boolean created = db.execute("insert_order",
                jdbc -> repository.insertIfAbsent(jdbc, newId, idempotencyKey, productId, quantity));
        Order order = db.execute("find_order_by_key", jdbc -> repository.findByIdempotencyKey(jdbc, idempotencyKey))
                .orElseThrow(() -> new IllegalStateException("Order for key " + idempotencyKey + " not found"));

        if (!created) {
            if (!order.sameRequest(productId, quantity)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            if (order.status().isFinal()) {
                meters.counter("faultlab.orders.replayed").increment();
                log.info("Idempotent replay of order {} ({})", order.orderId(), order.status());
                return new PlaceOrderResult(order, true, 0);
            }
            log.info("Re-attempting unresolved order {} ({}) for repeated key", order.orderId(), order.status());
        }

        ReservationOutcome outcome = reserve(order);
        Order updated = resolve(order, outcome);
        return new PlaceOrderResult(updated, !created, outcome.attempts());
    }

    /** Re-drives an unresolved order. Safe because the inventory reservation is idempotent on the order id. */
    public Order reconcile(Order order) {
        return resolve(order, reserve(order));
    }

    public Order get(UUID orderId) {
        return db.execute("find_order", jdbc -> repository.findById(jdbc, orderId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private ReservationOutcome reserve(Order order) {
        return inventory.reserve(new ReservationRequest(order.orderId(), order.productId(), order.quantity()));
    }

    private Order resolve(Order order, ReservationOutcome outcome) {
        OrderStatus status = switch (outcome.result()) {
            case RESERVED -> OrderStatus.CONFIRMED;
            case REJECTED -> OrderStatus.REJECTED;
            case NOT_SENT -> OrderStatus.FAILED;
            case UNKNOWN -> OrderStatus.RESERVATION_UNKNOWN;
        };
        Order updated = db.execute("update_order_status", jdbc ->
                repository.updateStatus(jdbc, order.orderId(), status, outcome.detail(), outcome.attempts())
                        .or(() -> repository.findById(jdbc, order.orderId())))
                .orElseThrow(() -> new OrderNotFoundException(order.orderId()));
        meters.counter("faultlab.orders", "status", updated.status().name()).increment();
        log.info("Order {} -> {} after {} downstream attempt(s)", updated.orderId(), updated.status(), outcome.attempts());
        return updated;
    }
}
