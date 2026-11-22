# FaultLab

A containerised lab for learning distributed-systems failure handling and observability:
two Spring Boot 4 services talking synchronously over HTTP, with real PostgreSQL, controllable
failures, retries/backoff/jitter, idempotency, circuit breaking, bulkheads, HikariCP experiments,
metrics, distributed traces, structured logs and k6 load tests.

```
 k6 ──► order-service :8080 ──RestClient──► inventory-service :8081
          │  Retry(manual|R4j) › CircuitBreaker › Bulkhead      │  failure modes, latency
          ▼                                                     ▼
        PostgreSQL  (schema order_service)        (schema inventory_service)
          │
  metrics ─► Prometheus ─┐     traces ─► OTel Collector ─► Tempo ─┐
  logs ─► Logstash ─► Elasticsearch (optional) ───────────────────┴─► Grafana
```

## Quick start (Windows PowerShell 7+, Docker Desktop)

```powershell
Copy-Item .env.example .env
./scripts/start-all.ps1            # add -Logging for Elasticsearch + Logstash
./scripts/smoke-test.ps1           # end-to-end verification
./scripts/run-load-test.ps1 -Test constant-load -Rate 50 -Duration 2m
./scripts/stop-all.ps1
```

| URL | What |
|---|---|
| http://localhost:8080/api/orders | Service A |
| http://localhost:8081/api/inventory/P-0001 | Service B |
| http://localhost:3000 | Grafana (admin/admin): *FaultLab → System Overview*, *Resilience and Database* |
| http://localhost:9090 | Prometheus |
| http://localhost:9200 | Elasticsearch (logging profile) |

## API

```bash
# Place an order (Idempotency-Key makes retries safe)
curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 7b0c6c1e-demo' -d '{"productId":"P-0001","quantity":1}'
curl localhost:8080/api/orders/<orderId>

# Inventory
curl localhost:8081/api/inventory/P-0001
curl -X POST localhost:8081/admin/failure-mode -H 'Content-Type: application/json' -d '{"mode":"FAIL_FIRST_2"}'
curl -X POST localhost:8081/admin/reset
```

| order-service response | Meaning |
|---|---|
| 201 `CONFIRMED` | reserved |
| 200 `CONFIRMED` | idempotent replay (`Idempotent-Replayed: true`) |
| 409 `REJECTED` | insufficient stock / unknown product (definitive) |
| 202 `RESERVATION_UNKNOWN` | request reached inventory but no definitive answer: reconciled later, or retry with the same key |
| 503 `FAILED` | inventory not called (circuit open / bulkhead full): safe to retry |
| 400 / 422 | invalid request / key reused with a different payload |

Failure modes: `NORMAL`, `FAIL_FIRST_2`, `ALWAYS_FAIL`, `SLOW_5_SECONDS`, `PROCESS_THEN_FAIL_ONCE`, `RANDOM_FAILURE`
(plus `slowLatencyMs`, `failureRate`, `baseLatencyMs`, `dbDelayMs`).

## Build & test

```bash
gradle wrapper                            # once, with a local Gradle 9.x (the wrapper jar is not committed)
./gradlew build                           # compile + unit + integration tests (Testcontainers needs Docker)
./gradlew :applications:order-service:test
docker compose -f applications/docker-compose.yml build
```

## Documentation
- [docs/architecture.md](docs/architecture.md): design, idempotency, resilience, observability, AWS migration
- [docs/setup-guide.md](docs/setup-guide.md): prerequisites, local vs Neon PostgreSQL, environment reference
- [docs/experiments.md](docs/experiments.md): guided resilience and HikariCP experiments
- [docs/troubleshooting.md](docs/troubleshooting.md)

## Repository layout
```
applications/        order-service, inventory-service, docker-compose.yml (apps + optional PostgreSQL)
observability/       docker-compose.yml + Prometheus, Grafana, Tempo, OTel Collector, Elasticsearch, Logstash config
database/migrations/ Flyway SQL per service (packaged into each jar at build time)
load-tests/          k6: smoke, constant-load, stress, spike, soak
scripts/             PowerShell: start/stop stacks, load tests, end-to-end smoke test
docs/                architecture, setup, experiments, troubleshooting
```
