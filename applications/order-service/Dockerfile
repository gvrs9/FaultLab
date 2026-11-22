# syntax=docker/dockerfile:1
# Build context: repository root (see applications/docker-compose.yml).
ARG GRADLE_IMAGE=gradle:jdk21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre

FROM ${GRADLE_IMAGE} AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY applications/order-service/build.gradle.kts applications/order-service/
COPY applications/inventory-service/build.gradle.kts applications/inventory-service/
# Resolve dependencies in a cached layer
RUN --mount=type=cache,target=/home/gradle/.gradle/caches \
    gradle --no-daemon -q :applications:order-service:dependencies --configuration runtimeClasspath > /dev/null
COPY database ./database
COPY applications/order-service/src applications/order-service/src
RUN --mount=type=cache,target=/home/gradle/.gradle/caches \
    gradle --no-daemon :applications:order-service:bootJar -x test

FROM ${RUNTIME_IMAGE}
ARG OTEL_AGENT_VERSION=2.20.1
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY --from=build /workspace/applications/order-service/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    OTEL_JAVAAGENT_ENABLED=true \
    LOGSTASH_ENABLED=false
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
# exec => java is PID 1 and receives SIGTERM for graceful shutdown
ENTRYPOINT ["sh", "-c", "if [ \"$OTEL_JAVAAGENT_ENABLED\" = \"true\" ]; then AGENT=-javaagent:/opt/otel/opentelemetry-javaagent.jar; fi; exec java $JAVA_OPTS $AGENT -Dlogstash.enabled=$LOGSTASH_ENABLED -jar /app/app.jar"]
