package com.faultlab.inventory.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL with schema-qualified names (no session-level search_path), which keeps it compatible
 * with PgBouncer transaction pooling (Neon pooled endpoint).
 */
@Repository
public class InventoryRepository {

    public Optional<Product> findProduct(JdbcClient jdbc, String productId) {
        return jdbc.sql("SELECT product_id, available, reserved FROM inventory_service.products WHERE product_id = :id")
                .param("id", productId)
                .query((rs, n) -> new Product(rs.getString("product_id"), rs.getInt("available"), rs.getInt("reserved")))
                .optional();
    }

    /** @return true if inserted, false if a reservation with this idempotency key already exists */
    public boolean insertReservationIfAbsent(JdbcClient jdbc, UUID reservationId, String key, UUID orderId,
                                             String productId, int quantity) {
        return jdbc.sql("""
                        INSERT INTO inventory_service.reservations
                            (reservation_id, idempotency_key, order_id, product_id, quantity, status)
                        VALUES (:rid, :key, :orderId, :productId, :qty, 'RESERVED')
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("rid", reservationId).param("key", key).param("orderId", orderId)
                .param("productId", productId).param("qty", quantity)
                .update() == 1;
    }

    /** Atomic conditional decrement: safe under concurrency without explicit locking. */
    public boolean decrementStock(JdbcClient jdbc, String productId, int quantity) {
        return jdbc.sql("""
                        UPDATE inventory_service.products
                        SET available = available - :qty, reserved = reserved + :qty, updated_at = now()
                        WHERE product_id = :id AND available >= :qty
                        """)
                .param("qty", quantity).param("id", productId)
                .update() == 1;
    }

    public Optional<Reservation> findReservation(JdbcClient jdbc, String key) {
        return jdbc.sql("""
                        SELECT reservation_id, idempotency_key, order_id, product_id, quantity, status, created_at
                        FROM inventory_service.reservations WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query(InventoryRepository::mapReservation)
                .optional();
    }

    private static Reservation mapReservation(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation(rs.getObject("reservation_id", UUID.class), rs.getString("idempotency_key"),
                rs.getObject("order_id", UUID.class), rs.getString("product_id"), rs.getInt("quantity"),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
