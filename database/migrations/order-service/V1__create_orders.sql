-- Owned by order-service. Flyway runs with schemas=order_service (created automatically).
CREATE TABLE order_service.orders (
    order_id             UUID         PRIMARY KEY,
    idempotency_key      VARCHAR(100) NOT NULL,
    product_id           VARCHAR(64)  NOT NULL,
    quantity             INTEGER      NOT NULL CHECK (quantity > 0),
    status               VARCHAR(32)  NOT NULL,
    failure_reason       VARCHAR(500),
    downstream_attempts  INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key)
);

-- Supports the reconciler, which re-checks orders whose reservation outcome is unknown.
CREATE INDEX idx_orders_unresolved
    ON order_service.orders (updated_at)
    WHERE status IN ('PENDING', 'RESERVATION_UNKNOWN');
