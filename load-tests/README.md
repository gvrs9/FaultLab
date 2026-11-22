# k6 load tests

Run through `scripts/run-load-test.ps1` (k6 runs in a container on `faultlab-net`, targeting
`http://order-service:8080`). Summaries are exported with `--summary-export` to `load-tests/results/`.

| Script | Purpose | Key variables |
|---|---|---|
| `smoke.js` | correctness check, 20 sequential orders | `ITERATIONS` |
| `constant-load.js` | ramp-up then constant arrival rate | `RATE` (10/25/50/100/200/500), `DURATION`, `RAMP_UP` |
| `stress.js` | steps 10 → 25 → 50 → 100 → 200 → 500 RPS | `STEP`, `MAX_RATE` |
| `spike.js` | baseline → sudden spike → recovery | `BASE_RATE`, `SPIKE_RATE` |
| `soak.js` | long-running constant load | `RATE`, `DURATION` |

Metrics reported: `http_reqs` (throughput), `orders_successful` / `orders_failed` (count + rate/s),
`orders_error_rate`, `http_req_duration` med (p50) / p90 / p95 / p99, and `retry_amplification`
(downstream HTTP attempts per order, from the `X-Downstream-Attempts` header; 1.0 = no retries).

Each request uses a random product out of 1000, each with 1,000,000,000 units, and quantity 1,
so inventory exhaustion never dominates the results.
