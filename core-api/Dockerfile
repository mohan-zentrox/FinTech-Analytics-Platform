# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Warm the dependency cache in its own layer.
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S ledger && adduser -S ledger -G ledger
RUN apk add --no-cache curl
COPY --from=build /build/target/ledger-core-api.jar app.jar
USER ledger
EXPOSE 8080

# Free-tier containers are commonly capped at 512 MB. Letting the JVM size its
# heap from the cgroup limit (instead of the host's total RAM) is what keeps it
# from being OOM-killed on startup there.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10   CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
