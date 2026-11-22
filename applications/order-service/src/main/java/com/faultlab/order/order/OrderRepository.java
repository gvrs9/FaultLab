package com.faultlab.order.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Schema-qualified SQL: no session state, so it also works behind PgBouncer transaction pooling. */
@Repository
public class OrderRepository {

    private static final String COLUMNS = """
            order_id, idempotency_key, product_id, quantity, status, failure_reason, downstream_attempts,
            created_at, updated_at""";

    /** @return true if inserted, false if an order with this idempotency key already exists */
    public boolean insertIfAbsent(JdbcClient jdbc, UUID orderId, String key, String productId, int quantity) {
        return jdbc.sql("""
                        INSERT INTO order_service.orders (order_id, idempotency_key, product_id, quantity, status)
                        VALUES (:id, :key, :productId, :qty, 'PENDING')
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("id", orderId).param("key", key).param("productId", productId).param("qty", quantity)
                .update() == 1;
    }

    public Optional<Order> findById(JdbcClient jdbc, UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_service.orders WHERE order_id = :id")
                .param("id", orderId).query(OrderRepository::map).optional();
    }

    public Optional<Order> findByIdempotencyKey(JdbcClient jdbc, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_service.orders WHERE idempotency_key = :key")
                .param("key", key).query(OrderRepository::map).optional();
    }

    /** Never overwrites a final status, so a late/unknown outcome cannot undo a confirmation. */
    public Optional<Order> updateStatus(JdbcClient jdbc, UUID orderId, OrderStatus status, String reason,
                                        int additionalAttempts) {
        return jdbc.sql("""
                        UPDATE order_service.orders
                        SET status = :status, failure_reason = :reason,
                            downstream_attempts = downstream_attempts + :attempts, updated_at = now()
                        WHERE order_id = :id AND status NOT IN ('CONFIRMED', 'REJECTED')
                        RETURNING """ + COLUMNS)
                .param("status", status.name()).param("reason", reason)
                .param("attempts", additionalAttempts).param("id", orderId)
                .query(OrderRepository::map).optional();
    }

    public List<Order> findUnresolved(JdbcClient jdbc, Duration minAge, int limit) {
        String sql = "SELECT " + COLUMNS + """
                 FROM order_service.orders
                WHERE status IN ('PENDING', 'RESERVATION_UNKNOWN')
                  AND updated_at < now() - make_interval(secs => :ageSeconds)
                ORDER BY updated_at
                LIMIT :limit
                """;
        return jdbc.sql(sql)
                .param("ageSeconds", (double) minAge.toSeconds()).param("limit", limit)
                .query(OrderRepository::map).list();
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(rs.getObject("order_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("product_id"), rs.getInt("quantity"), OrderStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"), rs.getInt("downstream_attempts"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
