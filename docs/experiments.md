# Experiments

Prerequisites: both stacks running (`./scripts/start-all.ps1`). Open Grafana → *FaultLab* and keep both
dashboards visible. Reset between experiments:
```powershell
Invoke-RestMethod -Method Post http://localhost:8081/admin/reset
Invoke-RestMethod -Method Post http://localhost:8080/admin/reset
```
Helper used below:
```powershell
function Set-Mode($body) { Invoke-RestMethod -Method Post http://localhost:8081/admin/failure-mode -ContentType 'application/json' -Body ($body | ConvertTo-Json) }
```
To change order-service configuration, edit `.env` and run
`docker compose -f applications/docker-compose.yml --env-file .env up -d order-service`.

---

## Part 1: Timeouts, retries, backoff and jitter

### 1.1 No retries (baseline)
`.env`: `RETRY_MODE=NONE`. `Set-Mode @{mode='RANDOM_FAILURE'; failureRate=0.3}`; run
`run-load-test.ps1 -Test constant-load -Rate 50`.
**Expect:** about 30 % of orders return `202 RESERVATION_UNKNOWN`, and retry amplification is 1.0.

### 1.2 Manual retries: linear vs exponential
`RETRY_MODE=MANUAL`, `RETRY_BACKOFF_STRATEGY=LINEAR`, then `EXPONENTIAL`. Same load.
**Expect:** success rate rises to about 97 % (0.3³ ≈ 2.7 % still fail). Amplification ≈ 1 + 0.3 + 0.09 ≈ 1.39.
Latency p99 grows by the backoff waits. Compare the two strategies in *Downstream latency*.

### 1.3 Jitter and synchronised retries
`Set-Mode @{mode='ALWAYS_FAIL'}` for 20 s under 100 RPS, then reset. Compare `RETRY_JITTER=NONE`
with `FULL`. **Expect:** without jitter, retries arrive in waves exactly `initial·multiplier^n` apart.
FULL jitter flattens those spikes in the *Service B RPS* panel.

### 1.4 Resilience4j Retry
`RETRY_MODE=RESILIENCE4J`: same behaviour as 1.2, with `resilience4j_retry_calls_total{kind}` populated.

### 1.5 Response timeout vs slow dependency
`Set-Mode @{mode='SLOW_5_SECONDS'}` with `HTTP_RESPONSE_TIMEOUT=2s`.
**Expect:** every attempt times out and is retried (timeouts are in `RETRY_EXCEPTIONS`), so each order takes
about 3 × 2 s plus backoff. Then check the inventory DB: the reservations **were** created.
A timeout is not a failure. Orders end up `RESERVATION_UNKNOWN`, and the reconciler confirms them within about 30 s.
Remove `java.net.http.HttpTimeoutException` from `RETRY_EXCEPTIONS` and compare.

### 1.6 Idempotency under lost responses
`Set-Mode @{mode='PROCESS_THEN_FAIL_ONCE'}`. Add `500` to `RETRY_STATUS_CODES` and compare with leaving it out.
**Expect:** with 500 retried, orders are `CONFIRMED` after 2 attempts.
`faultlab_inventory_reservations_total{outcome="replayed"}` rises, and stock is decremented exactly once per order:
```sql
SELECT count(*), sum(quantity) FROM inventory_service.reservations;   -- equals confirmed orders
```

## Part 2: Circuit breaker and bulkhead

### 2.1 Opening, half-open, closing
`Set-Mode @{mode='ALWAYS_FAIL'}` under 25 RPS for 60 s, then reset.
**Expect:** after `CB_MINIMUM_CALLS` the state goes CLOSED → OPEN. Orders then return `503 FAILED` in
milliseconds, and inventory RPS drops to about 0. Every `CB_WAIT_OPEN` (10 s) the breaker goes HALF_OPEN and lets
`CB_HALF_OPEN_CALLS` probes through. After the reset it closes again.

### 2.2 Slow-call breaker
`Set-Mode @{mode='NORMAL'; baseLatencyMs=1800}` (below the 2 s timeout, above `CB_SLOW_CALL_DURATION=1500ms`).
**Expect:** no errors, but the slow-call rate exceeds 80 % and the breaker opens, which protects A's threads.

### 2.3 Cascading failure without protection
`CB_ENABLED=false`, `BULKHEAD_ENABLED=false`, `RETRY_MAX_ATTEMPTS=3`, `HTTP_RESPONSE_TIMEOUT=10s`,
`Set-Mode @{mode='SLOW_5_SECONDS'}`, then `run-load-test.ps1 -Test constant-load -Rate 100`.
**Expect:** order-service Tomcat busy threads hit `TOMCAT_MAX_THREADS` and latency climbs to the timeout.
Health checks may start failing. Repeat with protections on and compare.

### 2.4 Bulkhead
`BULKHEAD_MAX_CONCURRENT=10`, `Set-Mode @{mode='NORMAL'; baseLatencyMs=500}`, 100 RPS.
**Expect:** about 20 RPS succeed (10 concurrent / 0.5 s). The rest are rejected immediately (`503 FAILED`,
bulkhead permits at 0), while A stays responsive.

## Part 3: HikariCP and the database

Relevant panels: *Hikari active / idle / pending*, *Connection acquisition time*, *Database latency*,
*Database timeouts*. Pool sizes are independent: `ORDER_DB_POOL_MAX_SIZE`, `INVENTORY_DB_POOL_MAX_SIZE`.
The artificial delay holds a connection for its duration (`pg_sleep` inside the transaction).
Change it at runtime: `Set-Mode @{mode='NORMAL'; dbDelayMs=200}` (inventory) or
`Invoke-RestMethod -Method Post http://localhost:8080/admin/db-delay -ContentType 'application/json' -Body '{"delayMs":200}'`.

### 3.1 Pool exhaustion
`INVENTORY_DB_POOL_MAX_SIZE=5`, inventory `dbDelayMs=200`, 50 RPS.
Capacity ≈ 5 / 0.2 s = 25 operations/s, which is less than 50/s demanded.
**Expect:** active = max = 5, pending grows, and acquisition time climbs towards `DB_CONNECTION_TIMEOUT_MS`.

### 3.2 Connection acquisition timeout
As 3.1 with `INVENTORY_DB_CONNECTION_TIMEOUT_MS=500`.
**Expect:** `hikaricp_connections_timeout_total` increases and inventory returns `503 Database temporarily unavailable`
(never 500, never hangs). order-service retries those 503s, and amplification rises.
Once the load stops, active drops to 0. Connections are released correctly.

### 3.3 Concurrent requests vs pool size (Little's law)
Hold `dbDelayMs=100` and step `-Rate 25, 50, 100, 200` with pool size 10.
**Expect:** the knee appears near 10 / 0.1 s = 100 RPS. Then double the pool and observe the knee move.
A bigger pool only helps until PostgreSQL itself or the CPU saturates.

### 3.4 Long-running queries
order-service `delayMs=2000` at 10 RPS. Usage time p95 ≈ 2 s; all other requests on that service queue behind.
Because order-service never holds a connection across the HTTP call, **inventory latency does not consume
order-service connections**. Verify by slowing inventory (`SLOW_5_SECONDS`) and noting that order-service
Hikari active stays low.

### 3.5 Database connection limits
Local only. Set `POSTGRES_MAX_CONNECTIONS=30`, `ORDER_DB_POOL_MAX_SIZE=20`, `INVENTORY_DB_POOL_MAX_SIZE=20`
(plus Flyway and admin connections). **Expect:** as the pools grow under load, PostgreSQL rejects connections
(`FATAL: sorry, too many clients already`). This appears as `faultlab_db_errors_total` and Hikari creation errors in logs.
Rule: Σ(max pool size × instances) + headroom ≤ `max_connections`.
**Do not run this against Neon or any hosted database.** Do not try to exhaust a hosted database's connection limit.

### 3.6 PgBouncer behaviour (Neon pooled endpoint)
With Neon configured, compare `DB_POOLED_URL` (pooler) with temporarily setting `DB_POOLED_URL` to the direct URL:
* The pooler multiplexes many client connections onto few server connections, so client pool sizes can exceed
  the server's limit. Hikari's `active` still counts *client* connections.
* Transaction pooling breaks session state. FaultLab's SQL is schema-qualified for this reason.
  Try adding `?currentSchema=inventory_service` and removing qualifiers: it only works reliably on the direct URL.
* Prepared statements: if you see `prepared statement ... already exists`, add `prepareThreshold=0`.
* Acquisition time includes the pooler hop. Compare *Connection acquisition time* and *Database latency* for both URLs
  at 25 RPS. Keep pool sizes modest (≤ 5 per service) on hosted tiers.

## Part 4: Load profiles

```powershell
./scripts/run-load-test.ps1 -Test smoke
foreach ($r in 10,25,50,100,200,500) { ./scripts/run-load-test.ps1 -Test constant-load -Rate $r -Duration 2m }
./scripts/run-load-test.ps1 -Test stress
./scripts/run-load-test.ps1 -Test spike -Extra @{ BASE_RATE='25'; SPIKE_RATE='500' }
./scripts/run-load-test.ps1 -Test soak -Rate 50 -Duration 1h
```
Record for each run: `http_reqs` rate, `orders_successful` and `orders_failed` per second, `orders_error_rate`,
`http_req_duration` med/p90/p95/p99 and `retry_amplification` avg. The JSON summaries are in `load-tests/results/`.

## Tracing and logs walkthrough
1. Grafana → Explore → Tempo → Search: service `order-service`, or TraceQL `{ status = error }`.
2. Open a trace from a `FAIL_FIRST_2` run. You'll see one server span in A, three client spans, and three server spans in B.
   Two of B's server spans have errors.
3. With the logging profile: *Logs for this span* opens Elasticsearch filtered by `trace_id`. The log lines show
   `retry_attempt` 1, 2 and 3, `http_status`, and the shared `correlation_id`.
