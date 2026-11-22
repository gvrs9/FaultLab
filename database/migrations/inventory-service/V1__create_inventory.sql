-- Owned by inventory-service. Flyway runs with schemas=inventory_service (created automatically).
CREATE TABLE inventory_service.products (
    product_id  VARCHAR(64) PRIMARY KEY,
    available   INTEGER     NOT NULL CHECK (available >= 0),
    reserved    INTEGER     NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory_service.reservations (
    reservation_id   UUID         PRIMARY KEY,
    idempotency_key  VARCHAR(100) NOT NULL,
    order_id         UUID         NOT NULL,
    product_id       VARCHAR(64)  NOT NULL REFERENCES inventory_service.products (product_id),
    quantity         INTEGER      NOT NULL CHECK (quantity > 0),
    status           VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- One reservation per idempotency key: retries and duplicates can never double-reserve.
    CONSTRAINT uq_reservations_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_reservations_order_id ON inventory_service.reservations (order_id);
