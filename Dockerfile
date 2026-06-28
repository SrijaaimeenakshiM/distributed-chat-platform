# ─────────────────────────────────────────────────────────────────────────────
# Dockerfile — Distributed Chat Platform (Spring Boot 3 / Java 21 / Maven)
#
# Multi-stage build:
#   Stage 1 (builder): Maven wrapper + source → fat JAR
#   Stage 2 (runtime): Minimal JRE 21, non-root user, layered JAR extraction
#
# WHY layered JAR:
#   Spring Boot's layered JAR (enabled in pom.xml via <layers><enabled>true</enabled>)
#   splits the fat JAR into:
#     - dependencies        (rarely change → cached Docker layer)
#     - spring-boot-loader  (rarely changes → cached)
#     - snapshot-dependencies (occasionally change)
#     - application         (changes every build → thin layer ~100KB)
#   Rebuilds only copy the changed application layer, making CI/CD push
#   times 10-50× faster after the first build.
#
# WHY non-root user:
#   Running as root inside a container violates least-privilege.
#   The "chatuser" UID 1001 has no write access to system paths.
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM first.
# Docker layer cache: if pom.xml hasn't changed, dependency download
# is skipped on subsequent builds (the most time-consuming step).
COPY mvnw mvnw
COPY .mvn/ .mvn/
COPY pom.xml pom.xml
RUN chmod +x mvnw

# Download all dependencies in a separate layer so they are cached
# independently of source code changes. -q suppresses verbose output.
# --no-transfer-progress keeps CI logs clean.
RUN ./mvnw dependency:go-offline --no-transfer-progress -q

# Copy source and build the fat JAR, skipping tests (tests run in CI separately)
COPY src/ src/
RUN ./mvnw package -DskipTests --no-transfer-progress

# Extract layered JAR into discrete directories for Docker layer optimisation.
# The JAR name pattern *.jar matches whatever version is in pom.xml.
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install curl for Docker healthcheck; tini as PID 1 init to forward signals
# correctly to the JVM (without tini, SIGTERM goes to the shell, not java,
# breaking Spring Boot's server.shutdown=graceful).
RUN apk add --no-cache curl tini

# Create non-root application user
RUN addgroup -S chatgroup && adduser -S chatuser -G chatgroup -u 1001

WORKDIR /app

# Copy layered JAR contents in dependency order (least → most frequently changed).
# Each COPY is a separate Docker layer; unchanged layers are served from cache.
COPY --from=builder --chown=chatuser:chatgroup /build/extracted/dependencies/          ./
COPY --from=builder --chown=chatuser:chatgroup /build/extracted/spring-boot-loader/    ./
COPY --from=builder --chown=chatuser:chatgroup /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=chatuser:chatgroup /build/extracted/application/           ./

# Switch to non-root user before exposing ports
USER chatuser

EXPOSE 8080

# JVM tuning flags for a containerised Java 21 application:
#   -XX:+UseContainerSupport          — respect cgroup CPU/memory limits (default in JDK 11+)
#   -XX:MaxRAMPercentage=75.0         — use 75% of container memory for heap
#   -XX:+UseZGC                       — Z Garbage Collector: low-latency, sub-ms pauses
#   -XX:+ZGenerational                — Java 21 generational ZGC (better throughput)
#   -Djava.security.egd=...urandom    — faster SecureRandom init (avoids /dev/random block)
#   -Dspring.threads.virtual.enabled  — enable Java 21 virtual threads for Tomcat
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseZGC \
               -XX:+ZGenerational \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.threads.virtual.enabled=true"

# tini as PID 1: forwards SIGTERM to the JVM so Spring Boot's graceful
# shutdown (server.shutdown=graceful in application.yml) fires correctly.
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# Docker healthcheck: poll /health every 30s, 3 failures = unhealthy.
# Nginx upstream checks also target this endpoint.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:8080/health | grep -q '"status":"UP"' || exit 1
