# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
RUN apk add --no-cache maven && mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S fintrack && adduser -S fintrack -G fintrack
USER fintrack

COPY --from=build /workspace/target/*.jar app.jar

# Footprint over throughput: the service idles at <0.01 vCPU and is billed per MB of RAM.
# Serial GC + a heap that shrinks back after GC + C1-only JIT (no C2 arenas/profiling data).
# Override per environment by setting JAVA_TOOL_OPTIONS on the Railway service — no rebuild needed.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx256m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
