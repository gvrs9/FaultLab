# Architecture

## Components

| Component | Port | Role |
|---|---|---|
| order-service (Service A) | 8080 | Accepts orders, persists them, reserves stock in B synchronously |
| inventory-service (Service B) | 8081 | Owns stock & reservations, injects failures on demand |
| PostgreSQL | 5432 | One database, two schemas: `order_service`, `inventory_service` (optional local container or Neon) |
| OpenTelemetry Collector | 4317/4318 | Receives OTLP traces from the Java agents, forwards to Tempo |
| Tempo | 3200 | Trace storage; also generates service-graph metrics into Prometheus |
| Prometheus | 9090 | Scrapes `/actuator/prometheus` every 5 s |
| Grafana | 3000 | Provisioned data sources and dashboards |
| Logstash → Elasticsearch | 5000 → 9200 | Optional structured log pipeline |

Two Compose projects (`applications/`, `observability/`) share the external network `faultlab-net`;
all container-to-container traffic uses Docker DNS names. Nothing depends on `localhost`
between containers.

## Request flow (POST /api/orders)

1. **Insert order** (`PENDING`) with `INSERT … ON CONFLICT (idempotency_key) DO NOTHING`.
   An existing key with a different payload → 422; with a final status → replayed (200/409).
2. **Reserve stock** with `POST /api/inventory/reservations`, `Idempotency-Key = orderId`,
   wrapped as `Retry( CircuitBreaker( Bulkhead( HTTP ) ) )`.
3. **Record the outcome** (`CONFIRMED` / `REJECTED` / `FAILED` / `RESERVATION_UNKNOWN`).
   A final status is never overwritten.

Each step uses its own short transaction. **No database connection is held during the remote
call**. Holding one is the classic way latency in B exhausts the pool in A.

## Idempotency (and why timeouts are not failures)

* Client → A: `Idempotency-Key` header, unique in `order_service.orders`.
* A → B: the order id, unique in `inventory_service.reservations`. B inserts the reservation and
  decrements stock in **one transaction**. Concurrent duplicates serialise on the unique index; the
  loser sees the winner's row and returns it as a replay (200). Insufficient stock rolls back the row.
* A timeout, connection reset or 5xx after a request was sent is treated as **unknown**, never as
  "not processed". Retrying is safe *only* because of the idempotency key. Unknown orders are
  resolved by the client retrying with the same key, or by the `OrderReconciler`, which replays the
  reservation every 30 s.
* `PROCESS_THEN_FAIL_ONCE` demonstrates exactly this: B commits and then returns 500.

## Resilience

| Concern | Implementation |
|---|---|
| Timeouts | JDK `HttpClient` connect timeout + per-request response timeout (`HTTP_CONNECT_TIMEOUT`, `HTTP_RESPONSE_TIMEOUT`) |
| Retry | `RETRY_MODE`: `MANUAL` (hand-written loop) or `RESILIENCE4J`, same policy & backoff for both |
| What is retried | only `RETRY_STATUS_CODES` (default 502/503/504) and `RETRY_EXCEPTIONS` (connect/timeouts, matched in the cause chain). Never 4xx, never 500, never circuit/bulkhead rejections |
| Backoff | `FIXED`, `LINEAR` (initial·n), `EXPONENTIAL` (initial·multiplier^(n-1)), capped by `RETRY_MAX_INTERVAL` |
| Jitter | `FULL` random(0,d) or `EQUAL` d/2 + random(0,d/2) |
| Circuit breaker | Resilience4j, count- or time-based window, failure-rate and slow-call thresholds, half-open probes; only 5xx/429/I/O failures are recorded |
| Bulkhead | Resilience4j semaphore bulkhead limiting concurrent calls to B |

Resilience4j is used through its core modules and `resilience4j-micrometer`, configured from
`faultlab.resilience.*` properties. No AOP or Boot auto-configuration is involved, so the same objects are
unit-tested without Spring.

## Observability

* **Metrics**: Micrometer + Prometheus registry. HTTP server/client histograms (p50–p99), JVM, CPU,
  threads, GC, HikariCP (active/idle/pending/max/min, acquire/usage time, timeouts), Resilience4j
  retry/circuit-breaker/bulkhead, and custom metrics: `faultlab_downstream_attempts_total`,
  `faultlab_downstream_calls_total` (retry amplification = attempts / calls),
  `faultlab_db_operation_seconds`, `faultlab_db_errors_total`, `faultlab_failure_*`, `faultlab_orders_total`.
  All tags are bounded (no ids or keys).
* **Traces**: the OpenTelemetry Java agent instruments Tomcat (server spans), the JDK HttpClient (client
  spans and W3C `traceparent` propagation) and JDBC. One order is one trace across both services.
  Handled exceptions are recorded on the active span by the exception handlers.
* **Logs**: Log4j2 JSON (`JsonTemplateLayout`) with timestamp, level, service name, trace_id, span_id,
  correlation_id, retry_attempt, http_status and exception fields. Async non-blocking appenders
  (events are dropped rather than blocking requests when the buffer is full). The Logstash TCP appender
  is switched on by `LOGSTASH_ENABLED=true`.

## Database configuration

| Variable | Used for |
|---|---|
| `DB_URL` | default for both application traffic and migrations |
| `DB_POOLED_URL` | application traffic (Neon pooler / PgBouncer), overrides `DB_URL` |
| `DB_DIRECT_URL` | Flyway migrations (Neon direct endpoint), overrides `DB_URL` |
| `DB_USERNAME`, `DB_PASSWORD` | credentials |

SQL is schema-qualified and uses no session state (`SET`, advisory locks, `search_path`), so it is
compatible with PgBouncer transaction pooling. Flyway runs against the direct connection, because DDL
and its locking need a real session.

## Future AWS migration (not implemented)

| Lab component | AWS target | Notes |
|---|---|---|
| Containers + Compose | **ECS on Fargate** | one service per app; images already stateless and env-driven; health check → ALB target group `/actuator/health/readiness` |
| Docker DNS | ECS Service Connect / Cloud Map | set `INVENTORY_BASE_URL` to the service-connect name |
| PostgreSQL | **RDS PostgreSQL** (+ RDS Proxy for pooling) | `DB_POOLED_URL` → RDS Proxy, `DB_DIRECT_URL` → instance; credentials from Secrets Manager injected as env |
| Prometheus | **Amazon Managed Prometheus** | ADOT collector scrapes `/actuator/prometheus` and remote-writes (SigV4) |
| Grafana | **Amazon Managed Grafana** | import the two dashboard JSONs; AMP + X-Ray/Tempo data sources |
| OTel Collector + Tempo | **AWS Distro for OpenTelemetry** → X-Ray (or self-hosted Tempo on ECS) | keep the Java agent; change `OTEL_EXPORTER_OTLP_ENDPOINT` to the ADOT sidecar |
| Logstash + Elasticsearch | **Amazon OpenSearch** or **CloudWatch Logs** | stdout JSON → FireLens/Fluent Bit → OpenSearch; or awslogs driver → CloudWatch Logs Insights |
| Container logs/metrics | **CloudWatch** | Container Insights for task CPU/memory |

Nothing in application code changes: all endpoints, credentials, pool sizes, retry settings and
telemetry/logging endpoints are already environment variables.
