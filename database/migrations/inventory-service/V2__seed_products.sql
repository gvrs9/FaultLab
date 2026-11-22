-- 1000 products with very large stock so load tests are never dominated by inventory exhaustion.
INSERT INTO inventory_service.products (product_id, available)
SELECT 'P-' || lpad(g::text, 4, '0'), 1000000000
FROM generate_series(1, 1000) AS g;

-- A deliberately scarce product for testing the "insufficient stock" path.
INSERT INTO inventory_service.products (product_id, available) VALUES ('P-LIMITED', 10);
