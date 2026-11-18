# syntax=docker/dockerfile:1.7

ARG NODE_VERSION=22.22.0

FROM --platform=$BUILDPLATFORM node:${NODE_VERSION}-bookworm-slim AS node

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS jdk21

# Gradle runs on JDK 17 and compiles against JDK 21. Providing both locally avoids a toolchain download.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
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
 && mkdir -p /var/lock/vds-enrich /var/lib/vds-discovery \
 && chown vds /var/lock/vds-enrich /var/lib/vds-discovery
WORKDIR /app
COPY --from=build /src/build/libs/vds-provider-grabber-1.0.0-all.jar app.jar
COPY LICENSE /app/LICENSE
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
