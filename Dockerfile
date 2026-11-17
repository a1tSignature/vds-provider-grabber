# syntax=docker/dockerfile:1.7

ARG NODE_VERSION=22.22.0

FROM node:${NODE_VERSION}-bookworm-slim AS node

FROM eclipse-temurin:21-jdk AS jdk21

# The Gradle daemon is pinned to JDK 17 (gradle/gradle-daemon-jvm.properties) and compiles with a
# JDK 21 toolchain; both are provided locally so the build never downloads a JDK.
FROM eclipse-temurin:17-jdk AS build
COPY --from=jdk21 /opt/java/openjdk /opt/jdk21
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version

COPY frontend frontend
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.npm \
    ./gradlew shadowJar --no-daemon \
      -Porg.gradle.java.installations.paths=/opt/jdk21 \
      -Porg.gradle.java.installations.auto-download=false

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="VDS Provider Grabber" \
      org.opencontainers.image.source="https://github.com/a1tSignature/vds-provider-grabber"
RUN useradd --system --uid 10001 --home-dir /app vds \
 && mkdir -p /var/lock/vds-enrich \
 && chown vds /var/lock/vds-enrich
WORKDIR /app
COPY --from=build /src/build/libs/vds-provider-grabber-1.0-SNAPSHOT-all.jar app.jar
# The enrichment process's launcher; the service ENTRYPOINT below does not use it.
COPY --chmod=0755 scripts/enrich-launch.sh /app/enrich-launch.sh

# Heap follows the container memory limit; an OOM kills the process so the restart policy can act
# instead of leaving a half-alive JVM.
ENV TZ=UTC \
    VDS_HTTP_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER vds
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS -o /dev/null http://localhost:8080/health/ready || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
