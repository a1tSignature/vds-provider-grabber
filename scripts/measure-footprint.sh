#!/usr/bin/env bash
# Measures the two numbers the service's whole design is budgeted against:
#   - cold start:  JVM process launch -> first 200 from GET /health/ready   (budget <= 5 s)
#   - idle RSS:    after a fixed 50-request load, measured with `ps -o rss=` INSIDE the
#                  eclipse-temurin:21-jre container (Linux)                 (budget <= 260 MB)
#
# Prerequisites (not done by this script):
#   - `docker compose up -d` (PostgreSQL), already migrated (e.g. by running the jar once, or
#     any Flyway-triggered boot)
#   - `src/main/resources/db/dev-seed.sql` applied against that empty, migrated catalogue
#   - the fat jar built: `./gradlew shadowJar` (build/libs/*-all.jar)
#
# Only the RSS number measured via `ps -o rss=` is directly comparable to the budget: it is read
# with `docker exec` inside the Linux `eclipse-temurin:21-jre` container regardless of the host
# OS running this script, because Docker Desktop also runs Linux containers on macOS. Do not
# substitute a host-level RSS reading (e.g. `ps` run directly on a macOS host) -- RSS accounting
# differs between macOS and Linux and is not comparable to the budget.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COLD_START_BUDGET_SECONDS="5"
RSS_BUDGET_MB="260"
HOST_PORT="18080"
JAVA_IMAGE="eclipse-temurin:21-jre"

JVM_FLAGS=(
  -Xmx192m
  -XX:MaxMetaspaceSize=128m
  -XX:ReservedCodeCacheSize=48m
  -XX:MaxDirectMemorySize=32m
  -Xss512k
  -XX:+UseSerialGC
  -XX:+ExitOnOutOfMemoryError
  -Duser.timezone=UTC
)

# Bootstrap-admin credentials for this measurement run only: 20 chars / <= 72 UTF-8 bytes, so
# VDS_BOOTSTRAP_ADMIN_PASSWORD's policy in loadConfig (>= 12 chars, <= 72 UTF-8 bytes) is met.
BOOTSTRAP_USER="footprint-admin"
BOOTSTRAP_PASSWORD="Footprint-Measure-2026!"
JWT_SECRET="footprint-measurement-script-jwt-secret-value"

JAR_PATH="$(ls build/libs/*-all.jar 2>/dev/null | head -n1 || true)"
if [[ -z "$JAR_PATH" ]]; then
  echo "ERROR: no shadow jar found under build/libs/*-all.jar -- run './gradlew shadowJar' first." >&2
  exit 1
fi

POSTGRES_CID="$(docker compose ps -q postgres || true)"
if [[ -z "$POSTGRES_CID" ]]; then
  echo "ERROR: the 'postgres' service is not running -- run 'docker compose up -d' (and apply dev-seed.sql) first." >&2
  exit 1
fi

NETWORK="$(docker inspect "$POSTGRES_CID" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' | head -n1)"
if [[ -z "$NETWORK" ]]; then
  echo "ERROR: could not determine the docker network of the 'postgres' service." >&2
  exit 1
fi

DB_NAME="${VDS_DB_NAME:-vds}"
DB_USER="${VDS_DB_USER:-vds}"
DB_PASSWORD="${VDS_DB_PASSWORD:-vds}"

CID=""
cleanup() {
  if [[ -n "$CID" ]]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

now_ms() {
  # python3 is used for a portable millisecond timestamp (available on both the macOS dev box
  # this was authored/validated on, and any target Linux box); falls back to whole-second
  # resolution via bash's SECONDS if python3 is somehow absent.
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(int(time.time() * 1000))'
  else
    echo "$((SECONDS * 1000))"
  fi
}

echo "Starting the service container (image: $JAVA_IMAGE, network: $NETWORK)..."
START_MS="$(now_ms)"
CID="$(docker run -d \
  --network "$NETWORK" \
  -e VDS_DB_URL="jdbc:postgresql://postgres:5432/${DB_NAME}" \
  -e VDS_DB_USER="${DB_USER}" \
  -e VDS_DB_PASSWORD="${DB_PASSWORD}" \
  -e VDS_JWT_SECRET="${JWT_SECRET}" \
  -e VDS_BOOTSTRAP_ADMIN_USERNAME="${BOOTSTRAP_USER}" \
  -e VDS_BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_PASSWORD}" \
  -e VDS_HTTP_PORT=8080 \
  -p "${HOST_PORT}:8080" \
  -v "$(pwd)/${JAR_PATH}:/app/app.jar:ro" \
  "$JAVA_IMAGE" \
  java "${JVM_FLAGS[@]}" -jar /app/app.jar)"

BASE_URL="http://localhost:${HOST_PORT}"
DEADLINE_MS=$((START_MS + COLD_START_BUDGET_SECONDS * 1000 + 5000)) # small grace margin before giving up entirely
READY=0
while [[ "$(now_ms)" -lt "$DEADLINE_MS" ]]; do
  if [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "${BASE_URL}/health/ready" 2>/dev/null || true)" == "200" ]]; then
    READY=1
    break
  fi
  sleep 0.05
done
END_MS="$(now_ms)"

if [[ "$READY" -ne 1 ]]; then
  echo "ERROR: GET /health/ready never returned 200 within the grace window. Container logs:" >&2
  docker logs "$CID" >&2 || true
  exit 1
fi

COLD_START_MS=$((END_MS - START_MS))
COLD_START_SECONDS="$(python3 -c "print(f'{${COLD_START_MS} / 1000:.3f}')" 2>/dev/null || echo "$((COLD_START_MS / 1000))")"
echo "Cold start: ${COLD_START_SECONDS} s (${COLD_START_MS} ms), budget <= ${COLD_START_BUDGET_SECONDS} s"

# --- fixed load: 1 login + 20x tariffs search + 20x providers list + 9x providers/{id} = 50 ---
echo "Running the fixed load (50 requests)..."

LOGIN_BODY="{\"username\":\"${BOOTSTRAP_USER}\",\"password\":\"${BOOTSTRAP_PASSWORD}\"}"
LOGIN_RESPONSE="$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$LOGIN_BODY")"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | jq -r '.token // empty')"
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: login did not return a token. Response: $LOGIN_RESPONSE" >&2
  exit 1
fi
echo "  login: OK (token acquired)"

OK_COUNT=0
TOTAL_CATALOGUE=0

request_ok() {
  local path="$1"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}${path}")"
  TOTAL_CATALOGUE=$((TOTAL_CATALOGUE + 1))
  if [[ "$code" == "200" ]]; then
    OK_COUNT=$((OK_COUNT + 1))
  else
    echo "  WARNING: GET ${path} returned ${code}, expected 200" >&2
  fi
}

for _ in $(seq 1 20); do
  request_ok "/api/v1/tariffs?minRamMb=1024&sort=priceUsd"
done

PROVIDERS_RESPONSE=""
for i in $(seq 1 20); do
  providers_code="$(curl -s -o /tmp/measure-footprint-providers.json -w '%{http_code}' -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers")"
  TOTAL_CATALOGUE=$((TOTAL_CATALOGUE + 1))
  if [[ "$providers_code" == "200" ]]; then
    OK_COUNT=$((OK_COUNT + 1))
    [[ $i -eq 1 ]] && PROVIDERS_RESPONSE="$(cat /tmp/measure-footprint-providers.json)"
  else
    echo "  WARNING: GET /api/v1/providers returned ${providers_code}, expected 200" >&2
  fi
done
rm -f /tmp/measure-footprint-providers.json

# Pull real seeded provider ids out of the first successful /api/v1/providers response instead of
# assuming ids 1..9 -- the 9 requests below then hit genuinely seeded providers.
PROVIDER_IDS=($(printf '%s' "$PROVIDERS_RESPONSE" | jq -r '.content[].id' 2>/dev/null))
if [[ "${#PROVIDER_IDS[@]}" -eq 0 ]]; then
  echo "ERROR: could not read any provider id from /api/v1/providers -- is dev-seed.sql applied?" >&2
  exit 1
fi

for i in $(seq 1 9); do
  idx=$(( (i - 1) % ${#PROVIDER_IDS[@]} ))
  request_ok "/api/v1/providers/${PROVIDER_IDS[$idx]}"
done

TOTAL_REQUESTS=$((TOTAL_CATALOGUE + 1)) # + login
echo "Load profile: ${TOTAL_REQUESTS} requests total (1 login + ${TOTAL_CATALOGUE} catalogue), ${OK_COUNT}/${TOTAL_CATALOGUE} catalogue requests returned 200"

if [[ "$OK_COUNT" -ne "$TOTAL_CATALOGUE" ]]; then
  echo "ERROR: not every catalogue request returned 200 -- the load profile measured errors, not real traffic." >&2
  exit 1
fi

# --- RSS, measured inside the Linux container ---
RSS_KB="$(docker exec "$CID" sh -c "ps -o rss= -p 1" | tr -d ' ')"
if [[ -z "$RSS_KB" ]]; then
  echo "ERROR: could not read RSS via 'ps -o rss=' inside the container." >&2
  exit 1
fi
RSS_MB="$(( RSS_KB / 1024 ))"
echo "RSS: ${RSS_MB} MB (${RSS_KB} kB), budget <= ${RSS_BUDGET_MB} MB"

echo
echo "jar: ${JAR_PATH} ($(du -h "$JAR_PATH" | cut -f1))"

EXIT_CODE=0
if (( COLD_START_MS > COLD_START_BUDGET_SECONDS * 1000 )); then
  echo "FAIL: cold start ${COLD_START_SECONDS}s exceeds the ${COLD_START_BUDGET_SECONDS}s budget." >&2
  EXIT_CODE=1
fi
if (( RSS_MB > RSS_BUDGET_MB )); then
  echo "FAIL: RSS ${RSS_MB}MB exceeds the ${RSS_BUDGET_MB}MB budget." >&2
  EXIT_CODE=1
fi

exit "$EXIT_CODE"
