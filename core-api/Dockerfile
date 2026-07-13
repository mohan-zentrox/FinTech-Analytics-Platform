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
COPY --from=build /build/target/ledger-core-api.jar app.jar
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
