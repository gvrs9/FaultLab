# Verification status

This repository was generated in a sandbox **without Docker and without access to Maven Central**
(dependency downloads were blocked with HTTP 403). Results are split into what was actually executed and
what still has to be run on a machine with Docker.

## Executed in the generation sandbox
| Check | Result |
|---|---|
| All 63 Java sources (50 main, 13 test) (main + test) parsed by `javac` (JDK 17 front-end, syntax only) | pass: no syntax errors; remaining errors were only unresolved third-party packages |
| No unresolved references to project-internal classes | pass |
| YAML (Compose ×2, Spring ×4, Prometheus, Tempo, Collector, Grafana provisioning, Logstash) and JSON (dashboards, log template, ES template) parse | pass (19 files) |
| `log4j2-spring.xml` well-formed | pass |
| k6 scripts parse as ES modules (`node --check`) | pass |
| Every `${VAR}` used by the Spring configs is passed through by `applications/docker-compose.yml` | pass |

## Not executed. Run these yourself, in this order:
```powershell
gradle wrapper; ./gradlew build                                   # compile + unit + Testcontainers tests
docker compose -f applications/docker-compose.yml build           # images
./scripts/start-all.ps1 -Logging                                  # containers + health
./scripts/smoke-test.ps1                                          # endpoints, idempotency, retries, CB, metrics, traces, logs
./scripts/run-load-test.ps1 -Test constant-load -Rate 50          # load
```
Not verified: Gradle dependency resolution, compilation against real APIs, test results, image builds,
container start-up, service-to-service calls, Flyway on PostgreSQL/Neon, trace propagation,
Prometheus/Grafana/Elasticsearch integration, and k6 execution. Treat every item above as unverified
until the commands pass.
