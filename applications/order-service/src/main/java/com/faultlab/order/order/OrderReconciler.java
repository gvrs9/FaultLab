package com.faultlab.order.order;

import com.faultlab.order.db.DbOperations;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves orders whose reservation outcome is unknown (timeouts, 5xx) or that were left PENDING,
 * by replaying the idempotent reservation call. Never assumes a timed-out request was not processed.
 */
@Component
@ConditionalOnProperty(prefix = "faultlab.reconciler", name = "enabled", havingValue = "true")
public class OrderReconciler {

    private static final Logger log = LogManager.getLogger(OrderReconciler.class);

    private final DbOperations db;
    private final OrderRepository repository;
    private final OrderService orders;
    private final ReconcilerProperties props;

    public OrderReconciler(DbOperations db, OrderRepository repository, OrderService orders, ReconcilerProperties props) {
        this.db = db;
        this.repository = repository;
        this.orders = orders;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${faultlab.reconciler.interval-ms:30000}",
               initialDelayString = "${faultlab.reconciler.interval-ms:30000}")
    public void reconcile() {
        try {
            List<Order> unresolved = db.execute("find_unresolved",
                    jdbc -> repository.findUnresolved(jdbc, props.minAge(), props.batchSize()));
            if (unresolved.isEmpty()) {
                return;
            }
            log.info("Reconciling {} unresolved order(s)", unresolved.size());
            for (Order order : unresolved) {
                Order result = orders.reconcile(order);
                if (result.status() == OrderStatus.RESERVATION_UNKNOWN || result.status() == OrderStatus.FAILED) {
                    break;   // inventory still unhealthy: stop and try again next cycle
                }
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation cycle failed", e);
        }
    }
}
