# syntax=docker/dockerfile:1

# ---- Build stage ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:17.0.20.1_1-jdk-jammy AS build
WORKDIR /workspace

# Wrapper and build scripts first: the Gradle distribution and the dependencies are downloaded in their own layer,
# which stays cached until one of these files changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src/ src/
# Tests run in CI before the image is built; they need Docker (Testcontainers), which is not available here.
# bootJar alone produces a single jar (the plain jar comes from the separate `jar` task), so the glob matches one file.
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Runtime stage -------------------------------------------------------------------------------------------------
# JRE only (no compiler, smaller attack surface). Ubuntu Jammy rather than Alpine: Temurin publishes its Alpine JRE for
# amd64 only, so it would not run natively on arm64 (Apple Silicon, Graviton), and glibc is the platform the JDK is
# primarily built and tested on. The cost is roughly 40 MB of image size.
FROM eclipse-temurin:17.0.20.1_1-jre-jammy

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# Least to most frequently changing, so that a code change only rebuilds the small application layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app:app
EXPOSE 8080

# Size the heap from the container's memory limit rather than the host's, and exit on OOM so that the orchestrator
# restarts the container instead of leaving it in an undefined state. Can be overridden with `-e JDK_JAVA_OPTIONS=...`.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# For plain Docker / Compose; Kubernetes ignores HEALTHCHECK and should use /actuator/health/liveness and /readiness.
# The start period leaves room for the Liquibase migrations against a remote database on first start.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS -o /dev/null http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
