The optional local PostgreSQL container (profile `local-db` in `applications/docker-compose.yml`)
creates an empty `faultlab` database. Each service's Flyway creates and migrates its own schema
(`order_service`, `inventory_service`), so no init scripts are needed and the same migrations
work unchanged on Neon.
