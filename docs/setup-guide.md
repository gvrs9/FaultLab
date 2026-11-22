# Setup guide

## Prerequisites
* Docker Desktop (Compose v2.20+ for optional `depends_on`), ~6 GB RAM for the full stack incl. Elasticsearch
* PowerShell 7+ (`pwsh`) for the scripts
* For building or testing outside Docker: JDK 21 or newer (JDK 25 works) and Gradle 9.x.
  Images are built inside Docker, so no local JDK is needed just to run the lab.

## 1. Configure
```powershell
Copy-Item .env.example .env
```
Never leave a variable present but empty. Comment it out instead, so the default applies.

## 2. Choose a database

### Local PostgreSQL (default)
`USE_LOCAL_DB=true` and `DB_URL=jdbc:postgresql://postgres:5432/faultlab`. `start-all.ps1` enables the
`local-db` Compose profile: a `postgres:17-alpine` container with the persistent volume `faultlab-pgdata`.

### Neon PostgreSQL
```dotenv
USE_LOCAL_DB=false
DB_POOLED_URL=jdbc:postgresql://ep-xxx-pooler.eu-central-1.aws.neon.tech/faultlab?sslmode=require
DB_DIRECT_URL=jdbc:postgresql://ep-xxx.eu-central-1.aws.neon.tech/faultlab?sslmode=require
DB_USERNAME=...
DB_PASSWORD=...
```
The services use the pooled URL for traffic and the direct URL for Flyway. No code changes are needed.
If you see `prepared statement "S_1" already exists` behind a pooler, append `&prepareThreshold=0` to `DB_POOLED_URL`.
Keep `*_DB_POOL_MAX_SIZE` small (for example 5) on free tiers.

## 3. Start
```powershell
./scripts/start-observability.ps1 [-Logging]   # Prometheus, Grafana, Tempo, Collector (+ ES, Logstash)
./scripts/start-all.ps1 [-Logging] [-LocalDb] [-NoBuild]
./scripts/smoke-test.ps1
```
Equivalent raw commands:
```bash
docker network create faultlab-net
docker compose -f observability/docker-compose.yml up -d                      # + --profile logging
docker compose -f applications/docker-compose.yml --env-file .env --profile local-db up -d --build
```

## 4. Stop
```powershell
./scripts/stop-all.ps1                 # keep data
./scripts/stop-all.ps1 -RemoveVolumes  # wipe DB, metrics, traces, logs
```

## 5. Running from an IDE
Start PostgreSQL (`docker compose -f applications/docker-compose.yml --profile local-db up -d postgres`), then run
each application with `SPRING_PROFILES_ACTIVE=local`. `application-local.yml` points at `localhost`.
Without the OTel agent the services run normally; traces are simply not exported.

## Environment variable reference

### Both services
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/faultlab` | default JDBC URL |
| `DB_POOLED_URL` / `DB_DIRECT_URL` | `DB_URL` | app traffic / Flyway |
| `DB_USERNAME` / `DB_PASSWORD` | `faultlab` | credentials |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | 10, 2 | Hikari (Compose: `ORDER_…` / `INVENTORY_…` prefixes) |
| `DB_CONNECTION_TIMEOUT_MS` | 3000 | Hikari acquisition timeout |
| `DB_IDLE_TIMEOUT_MS`, `DB_MAX_LIFETIME_MS`, `DB_LEAK_DETECTION_MS` | 300000, 1200000, 0 | Hikari |
| `DB_SOCKET_TIMEOUT_S` | 30 | pgjdbc socket timeout |
| `DB_ARTIFICIAL_DELAY` | `0ms` | `pg_sleep` inside every DB operation |
| `SERVER_PORT` | 8080 / 8081 | HTTP port |
| `TOMCAT_MAX_THREADS`, `TOMCAT_ACCEPT_COUNT` | 200, 100 | servlet thread pool |
| `SHUTDOWN_TIMEOUT` | `20s` | graceful shutdown |
| `OTEL_JAVAAGENT_ENABLED` | `true` | attach the agent |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT` | per service, `http://otel-collector:4318` | tracing |
| `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` | `parentbased_traceidratio`, `1.0` | sampling |
| `LOGSTASH_ENABLED`, `LOGSTASH_HOST`, `LOGSTASH_PORT` | `false`, `logstash`, `5000` | log shipping |
| `FAULTLAB_LOG_LEVEL`, `ROOT_LOG_LEVEL` | `INFO` | log levels |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 …` | JVM flags |

### order-service
| Variable | Default |
|---|---|
| `INVENTORY_BASE_URL` | `http://inventory-service:8081` |
| `HTTP_CONNECT_TIMEOUT` / `HTTP_RESPONSE_TIMEOUT` | `500ms` / `2s` |
| `RETRY_ENABLED`, `RETRY_MODE` | `true`, `RESILIENCE4J` (`NONE`/`MANUAL`/`RESILIENCE4J`) |
| `RETRY_MAX_ATTEMPTS` | 3 (total, incl. first) |
| `RETRY_STATUS_CODES` | `502,503,504` |
| `RETRY_EXCEPTIONS` | `java.net.ConnectException,java.net.http.HttpTimeoutException,java.net.SocketTimeoutException` |
| `RETRY_BACKOFF_STRATEGY` | `EXPONENTIAL` (`FIXED`/`LINEAR`/`EXPONENTIAL`) |
| `RETRY_INITIAL_INTERVAL`, `RETRY_MULTIPLIER`, `RETRY_MAX_INTERVAL` | `100ms`, `2.0`, `2s` |
| `RETRY_JITTER` | `FULL` (`NONE`/`FULL`/`EQUAL`) |
| `CB_ENABLED`, `CB_FAILURE_RATE_THRESHOLD`, `CB_SLOW_CALL_RATE_THRESHOLD`, `CB_SLOW_CALL_DURATION` | `true`, 50, 80, `1500ms` |
| `CB_SLIDING_WINDOW_TYPE`, `CB_SLIDING_WINDOW_SIZE`, `CB_MINIMUM_CALLS` | `COUNT_BASED`, 20, 10 |
| `CB_WAIT_OPEN`, `CB_HALF_OPEN_CALLS`, `CB_MAX_WAIT_HALF_OPEN` | `10s`, 5, `0s` |
| `BULKHEAD_ENABLED`, `BULKHEAD_MAX_CONCURRENT`, `BULKHEAD_MAX_WAIT` | `true`, 50, `0ms` |
| `RECONCILER_ENABLED`, `RECONCILER_INTERVAL_MS`, `RECONCILER_MIN_AGE`, `RECONCILER_BATCH_SIZE` | `true`, 30000, `30s`, 50 |

### inventory-service
| Variable | Default |
|---|---|
| `FAILURE_MODE` | `NORMAL` |
| `FAILURE_SLOW_LATENCY_MS` | 5000 |
| `FAILURE_RATE` | 0.3 |
| `FAILURE_BASE_LATENCY_MS` | 0 |

### Compose-only
`USE_LOCAL_DB`, `POSTGRES_MAX_CONNECTIONS`, `*_HOST_PORT`, `ORDER_CPUS/MEMORY`, `INVENTORY_CPUS/MEMORY`,
`DEPLOY_ENV`, `GRAFANA_ADMIN_USER/PASSWORD`, image overrides (`POSTGRES_IMAGE`, `PROMETHEUS_IMAGE`, `GRAFANA_IMAGE`,
`TEMPO_IMAGE`, `OTEL_COLLECTOR_IMAGE`, `ELASTICSEARCH_IMAGE`, `LOGSTASH_IMAGE`).
