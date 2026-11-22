# Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `network faultlab-net declared as external, but could not be found` | `docker network create faultlab-net` (the scripts do this) |
| order-service unhealthy, logs show `Connection to postgres:5432 refused` | local DB not started: use `-LocalDb` or `USE_LOCAL_DB=true`; or `DB_URL` points elsewhere |
| `UnknownHostException: postgres` with Neon | `DB_POOLED_URL` / `DB_DIRECT_URL` not set, or set but empty in `.env` (comment unused lines out) |
| Flyway `Unable to obtain connection` on Neon | wrong `DB_DIRECT_URL` or missing `sslmode=require`; migrations must use the direct (non-pooler) endpoint |
| `prepared statement "S_x" already exists` | PgBouncer transaction mode: append `&prepareThreshold=0` to `DB_POOLED_URL` |
| Docker build fails downloading the OTel agent | no internet from the build: pre-download the jar and change `ADD` to `COPY`, or pass `--build-arg OTEL_AGENT_VERSION=<existing version>` |
| Gradle dependency resolution fails in the build | the build needs access to Maven Central (corporate proxy: set `GRADLE_OPTS` proxy flags in the Dockerfile) |
| No traces in Tempo | `OTEL_JAVAAGENT_ENABLED=true`? Collector up (`http://localhost:13133`)? Apps started after observability (agent retries anyway) |
| Trace shows two separate traces instead of one | a proxy stripped `traceparent`; the JDK HttpClient instrumentation must be active (agent ≥ 2.x) |
| Grafana Elasticsearch data source errors | logging profile not running: `start-observability.ps1 -Logging` |
| No logs in Elasticsearch | `LOGSTASH_ENABLED=true` in `.env`, then restart the apps; check `docker logs faultlab-observability-logstash-1` |
| Elasticsearch exits with code 137 | not enough memory for Docker: raise the Docker Desktop memory limit (≥ 6 GB) or lower `ES_JAVA_OPTS` |
| `max virtual memory areas vm.max_map_count [65530] is too low` | WSL2: `wsl -d docker-desktop sysctl -w vm.max_map_count=262144` |
| Prometheus target DOWN | the target container isn't on `faultlab-net` or isn't running; check `http://localhost:9090/targets` |
| k6 `dial tcp: lookup order-service` | run k6 through `run-load-test.ps1` (it joins `faultlab-net`); from the host use `-BaseUrl http://host.docker.internal:8080` |
| Orders stuck in `RESERVATION_UNKNOWN` | inventory is still failing; the reconciler retries every `RECONCILER_INTERVAL_MS` and stops a cycle on the first failure |
| Every order returns `503 FAILED` | the circuit is open: check the *Circuit breaker state* panel and wait `CB_WAIT_OPEN` after fixing B |
| `./gradlew` not found | run `gradle wrapper` once with a local Gradle 9.x (the wrapper jar isn't committed) |
| Testcontainers tests fail with "Could not find a valid Docker environment" | `*IntegrationTest` classes need a running Docker engine; start Docker Desktop and rerun `./gradlew test` |
