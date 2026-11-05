#!/usr/bin/env bash
# Measures the numbers the service's whole design is budgeted against.
#
# Default invocation (no arguments) -- the vds-001 profile, unchanged:
#   - cold start:  JVM process launch -> first 200 from GET /health/ready   (budget <= 5 s)
#   - idle RSS:    after a fixed 50-request load, measured with `ps -o rss=` INSIDE the
#                  eclipse-temurin:21-jre container (Linux)                 (budget <= 260 MB)
#
# `--crawler` (vds-002 req. 41, task vds-002.9) -- three further measurements with the same image,
# the same JVM flags and the same 50-request load profile, against a baseline measured in the very
# same invocation (never copied from a knowledge note, which would compare against another day's
# machine):
#   0. baseline       no VDS_CRAWLER_* variable at all                 the number 1 and 2 use
#   1. crawler off    VDS_CRAWLER_ENABLED=false                        baseline +- 3 MB
#   2. crawler idle   VDS_CRAWLER_ENABLED=true, no adapter selected    <= baseline + 15 MB
#   3. crawler peak   one `example-static` run over a 1 MiB page       <= 300 MB
#                     (RSS sampled every 200 ms while the run is in flight, maximum reported)
#
# Profile 3 serves `scripts/footprint-fixture/` -- a 1 MiB `vps.html` plus an allowing
# `robots.txt` -- from a throwaway `python3` HTTP server on the host, which the container reaches
# as `host.docker.internal` (`--add-host=host.docker.internal:host-gateway`, one line that works
# on Docker Desktop and on Linux alike). That server binds every interface, because it has to be
# reachable from inside the container, but only for the duration of the measurement: the cleanup
# trap kills it. `robots.txt` is served rather than switched off with
# VDS_CRAWLER_RESPECT_ROBOTS=false, so the measured run goes through the crawler's real robots
# path. The run writes the `example-static` reference provider into the measured database through
# the admin API, exactly as a production run would; re-running is idempotent (the second run
# creates nothing).
#
# Measure, do not optimise: a profile over its limit is reported and exits non-zero. That is never
# a reason to lower a JVM flag, drop a dependency, trim the load or raise the limit -- it is the
# trigger to reopen vault/06-decisions/vds-002-adr-01-in-process-crawler-module.md.
#
# Prerequisites (not done by this script):
#   - `docker compose up -d` (PostgreSQL), already migrated (e.g. by running the jar once, or
#     any Flyway-triggered boot)
#   - `src/main/resources/db/dev-seed.sql` applied against that empty, migrated catalogue
#   - the fat jar built: `./gradlew shadowJar` (build/libs/*-all.jar)
#   - `docker`, `curl`, `jq`, `python3` on the host; no other tool is used
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

# --- the three crawler limits of spec req. 41 / ADR-01 ---
CRAWLER_DISABLED_TOLERANCE_MB="3"
CRAWLER_IDLE_MAX_DELTA_MB="15"
CRAWLER_PEAK_LIMIT_MB="300"
CRAWLER_PEAK_SAMPLE_SECONDS="0.2"
CRAWLER_RUN_TIMEOUT_SECONDS="120"
FIXTURE_DIR="scripts/footprint-fixture"
FIXTURE_PORT="18099"
# The service account the crawler logs in as, created through POST /api/v1/admin/users below.
# Fixed on purpose: a second invocation reuses the account the first one created (409 is fine).
CRAWLER_USER="crawler-footprint"
CRAWLER_PASSWORD="Footprint-Crawler-2026!"

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

PROFILE="default"
case "${1:-}" in
  "")
    PROFILE="default"
    ;;
  --crawler)
    PROFILE="crawler"
    ;;
  *)
    echo "usage: $(basename "$0") [--crawler]" >&2
    exit 2
    ;;
esac

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
FIXTURE_PID=""

# `wait` reaps the killed python3 job inside this redirection, so bash does not print its
# "Terminated: 15 python3 - ... <<PYTHON" job notice -- which would dump the whole heredoc into
# the measurement output.
stop_fixture_server() {
  if [[ -n "$FIXTURE_PID" ]]; then
    { kill "$FIXTURE_PID" && wait "$FIXTURE_PID"; } >/dev/null 2>&1 || true
    FIXTURE_PID=""
  fi
}

cleanup() {
  if [[ -n "$CID" ]]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
  fi
  stop_fixture_server
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

BASE_URL="http://localhost:${HOST_PORT}"

# ---------------------------------------------------------------------------------------------
# the measurement steps, each usable by every profile
# ---------------------------------------------------------------------------------------------

# Launches the jar under measurement and waits for readiness. Extra `-e`/`--add-host` arguments
# are taken from the global SERVICE_DOCKER_ARGS array, so the JVM flags, the image and the
# network stay identical across profiles. Sets CID, COLD_START_MS and COLD_START_SECONDS.
start_service() {
  echo "Starting the service container (image: $JAVA_IMAGE, network: $NETWORK)..."
  local start_ms end_ms deadline_ms ready=0
  start_ms="$(now_ms)"
  CID="$(docker run -d \
    --network "$NETWORK" \
    -e VDS_DB_URL="jdbc:postgresql://postgres:5432/${DB_NAME}" \
    -e VDS_DB_USER="${DB_USER}" \
    -e VDS_DB_PASSWORD="${DB_PASSWORD}" \
    -e VDS_JWT_SECRET="${JWT_SECRET}" \
    -e VDS_BOOTSTRAP_ADMIN_USERNAME="${BOOTSTRAP_USER}" \
    -e VDS_BOOTSTRAP_ADMIN_PASSWORD="${BOOTSTRAP_PASSWORD}" \
    -e VDS_HTTP_PORT=8080 \
    ${SERVICE_DOCKER_ARGS[@]+"${SERVICE_DOCKER_ARGS[@]}"} \
    -p "${HOST_PORT}:8080" \
    -v "$(pwd)/${JAR_PATH}:/app/app.jar:ro" \
    "$JAVA_IMAGE" \
    java "${JVM_FLAGS[@]}" -jar /app/app.jar)"

  deadline_ms=$((start_ms + COLD_START_BUDGET_SECONDS * 1000 + 5000)) # small grace margin before giving up entirely
  while [[ "$(now_ms)" -lt "$deadline_ms" ]]; do
    if [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "${BASE_URL}/health/ready" 2>/dev/null || true)" == "200" ]]; then
      ready=1
      break
    fi
    sleep 0.05
  done
  end_ms="$(now_ms)"

  if [[ "$ready" -ne 1 ]]; then
    echo "ERROR: GET /health/ready never returned 200 within the grace window. Container logs:" >&2
    docker logs "$CID" >&2 || true
    exit 1
  fi

  COLD_START_MS=$((end_ms - start_ms))
  COLD_START_SECONDS="$(python3 -c "print(f'{${COLD_START_MS} / 1000:.3f}')" 2>/dev/null || echo "$((COLD_START_MS / 1000))")"
  echo "Cold start: ${COLD_START_SECONDS} s (${COLD_START_MS} ms), budget <= ${COLD_START_BUDGET_SECONDS} s"
}

stop_service() {
  if [[ -n "$CID" ]]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
    CID=""
  fi
}

# --- fixed load: 1 login + 20x tariffs search + 20x providers list + 9x providers/{id} = 50 ---
run_load() {
  echo "Running the fixed load (50 requests)..."

  local login_body login_response
  login_body="{\"username\":\"${BOOTSTRAP_USER}\",\"password\":\"${BOOTSTRAP_PASSWORD}\"}"
  login_response="$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$login_body")"
  TOKEN="$(printf '%s' "$login_response" | jq -r '.token // empty')"
  if [[ -z "$TOKEN" ]]; then
    echo "ERROR: login did not return a token. Response: $login_response" >&2
    exit 1
  fi
  echo "  login: OK (token acquired)"

  OK_COUNT=0
  TOTAL_CATALOGUE=0

  for _ in $(seq 1 20); do
    request_ok "/api/v1/tariffs?minRamMb=1024&sort=priceUsd"
  done

  local providers_response="" providers_code i idx
  for i in $(seq 1 20); do
    providers_code="$(curl -s -o /tmp/measure-footprint-providers.json -w '%{http_code}' -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers")"
    TOTAL_CATALOGUE=$((TOTAL_CATALOGUE + 1))
    if [[ "$providers_code" == "200" ]]; then
      OK_COUNT=$((OK_COUNT + 1))
      [[ $i -eq 1 ]] && providers_response="$(cat /tmp/measure-footprint-providers.json)"
    else
      echo "  WARNING: GET /api/v1/providers returned ${providers_code}, expected 200" >&2
    fi
  done
  rm -f /tmp/measure-footprint-providers.json

  # Pull real seeded provider ids out of the first successful /api/v1/providers response instead of
  # assuming ids 1..9 -- the 9 requests below then hit genuinely seeded providers.
  local provider_ids
  provider_ids=($(printf '%s' "$providers_response" | jq -r '.content[].id' 2>/dev/null))
  if [[ "${#provider_ids[@]}" -eq 0 ]]; then
    echo "ERROR: could not read any provider id from /api/v1/providers -- is dev-seed.sql applied?" >&2
    exit 1
  fi

  for i in $(seq 1 9); do
    idx=$(( (i - 1) % ${#provider_ids[@]} ))
    request_ok "/api/v1/providers/${provider_ids[$idx]}"
  done

  local total_requests=$((TOTAL_CATALOGUE + 1)) # + login
  echo "Load profile: ${total_requests} requests total (1 login + ${TOTAL_CATALOGUE} catalogue), ${OK_COUNT}/${TOTAL_CATALOGUE} catalogue requests returned 200"

  if [[ "$OK_COUNT" -ne "$TOTAL_CATALOGUE" ]]; then
    echo "ERROR: not every catalogue request returned 200 -- the load profile measured errors, not real traffic." >&2
    exit 1
  fi
}

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

# --- RSS, measured inside the Linux container ---
read_rss_kb() {
  local rss_kb
  rss_kb="$(docker exec "$CID" sh -c "ps -o rss= -p 1" | tr -d ' ')"
  if [[ -z "$rss_kb" ]]; then
    echo "ERROR: could not read RSS via 'ps -o rss=' inside the container." >&2
    exit 1
  fi
  echo "$rss_kb"
}

jar_line() {
  echo "jar: ${JAR_PATH} ($(du -h "$JAR_PATH" | cut -f1))"
}

# ---------------------------------------------------------------------------------------------
# profiles
# ---------------------------------------------------------------------------------------------

# The vds-001 profile. Its output lines are the script's original contract -- do not reword them.
profile_default() {
  SERVICE_DOCKER_ARGS=()
  start_service
  run_load

  local rss_kb rss_mb
  rss_kb="$(read_rss_kb)"
  rss_mb="$(( rss_kb / 1024 ))"
  echo "RSS: ${rss_mb} MB (${rss_kb} kB), budget <= ${RSS_BUDGET_MB} MB"

  echo
  jar_line

  EXIT_CODE=0
  if (( COLD_START_MS > COLD_START_BUDGET_SECONDS * 1000 )); then
    echo "FAIL: cold start ${COLD_START_SECONDS}s exceeds the ${COLD_START_BUDGET_SECONDS}s budget." >&2
    EXIT_CODE=1
  fi
  if (( rss_mb > RSS_BUDGET_MB )); then
    echo "FAIL: RSS ${rss_mb}MB exceeds the ${RSS_BUDGET_MB}MB budget." >&2
    EXIT_CODE=1
  fi
}

# One idle crawler profile: start with the given environment, drive the standard load, read RSS.
# Echoes nothing; sets PROFILE_RSS_KB / PROFILE_RSS_MB / PROFILE_COLD_START_MS.
measure_idle_profile() {
  start_service
  run_load
  PROFILE_RSS_KB="$(read_rss_kb)"
  PROFILE_RSS_MB="$(( PROFILE_RSS_KB / 1024 ))"
  PROFILE_COLD_START_MS="$COLD_START_MS"
  stop_service
}

# Serves $FIXTURE_DIR to the container: the 1 MiB page for any path, robots.txt for /robots.txt.
# `python3 -m http.server` is not used because it would answer the extensionless `/vps` the
# adapter asks for with `application/octet-stream`, which the crawler's content-type allowlist
# (text/html, application/xhtml+xml, application/json, text/plain) rejects.
start_fixture_server() {
  if [[ ! -f "${FIXTURE_DIR}/vps.html" || ! -f "${FIXTURE_DIR}/robots.txt" ]]; then
    echo "ERROR: ${FIXTURE_DIR}/vps.html and ${FIXTURE_DIR}/robots.txt are required for the peak profile." >&2
    exit 1
  fi
  python3 - "$FIXTURE_PORT" "$FIXTURE_DIR" <<'PYTHON' &
import http.server
import os
import sys

port, root = int(sys.argv[1]), sys.argv[2]
with open(os.path.join(root, "vps.html"), "rb") as handle:
    page = handle.read()
with open(os.path.join(root, "robots.txt"), "rb") as handle:
    robots = handle.read()


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body, content_type = (
            (robots, "text/plain; charset=utf-8")
            if self.path.split("?")[0] == "/robots.txt"
            else (page, "text/html; charset=utf-8")
        )
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("0.0.0.0", port), Fixture).serve_forever()
PYTHON
  FIXTURE_PID=$!
  local deadline_ms=$(( $(now_ms) + 5000 ))
  while [[ "$(now_ms)" -lt "$deadline_ms" ]]; do
    if [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "http://localhost:${FIXTURE_PORT}/robots.txt" 2>/dev/null || true)" == "200" ]]; then
      echo "  fixture server: serving ${FIXTURE_DIR} ($(du -h "${FIXTURE_DIR}/vps.html" | cut -f1) page) on port ${FIXTURE_PORT}"
      return 0
    fi
    sleep 0.05
  done
  echo "ERROR: the python3 fixture server did not come up on port ${FIXTURE_PORT}." >&2
  exit 1
}

# The EDITOR account the crawler authenticates as. 409 means an earlier invocation created it.
create_service_account() {
  local code
  code="$(curl -s -o /tmp/measure-footprint-user.json -w '%{http_code}' -X POST "${BASE_URL}/api/v1/admin/users" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${CRAWLER_USER}\",\"password\":\"${CRAWLER_PASSWORD}\",\"roles\":[\"EDITOR\"]}")"
  if [[ "$code" != "201" && "$code" != "409" ]]; then
    echo "ERROR: creating the crawler service account returned ${code}: $(cat /tmp/measure-footprint-user.json)" >&2
    rm -f /tmp/measure-footprint-user.json
    exit 1
  fi
  rm -f /tmp/measure-footprint-user.json
  echo "  service account '${CRAWLER_USER}' (EDITOR): $( [[ "$code" == "201" ]] && echo "created" || echo "already present" )"
}

crawl_run_status() {
  curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/admin/crawl/runs/$1" 2>/dev/null | jq -r '.status // empty'
}

# Triggers one run and samples RSS every $CRAWLER_PEAK_SAMPLE_SECONDS until the run is terminal.
# Sets PEAK_RSS_KB, PEAK_RSS_MB, PEAK_SAMPLES and RUN_ID.
measure_peak_during_run() {
  local trigger_response rss status deadline_ms
  # An empty-ish body falls back to VDS_CRAWLER_ADAPTERS, i.e. the operator's own selection.
  trigger_response="$(curl -fsS -X POST "${BASE_URL}/api/v1/admin/crawl/run" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"dryRun":false}')"
  RUN_ID="$(printf '%s' "$trigger_response" | jq -r '.runId // empty')"
  if [[ -z "$RUN_ID" ]]; then
    echo "ERROR: POST /api/v1/admin/crawl/run did not return a runId. Response: $trigger_response" >&2
    exit 1
  fi
  echo "  crawl run ${RUN_ID} triggered; sampling RSS every ${CRAWLER_PEAK_SAMPLE_SECONDS}s until it is terminal"

  PEAK_RSS_KB=0
  PEAK_SAMPLES=0
  deadline_ms=$(( $(now_ms) + CRAWLER_RUN_TIMEOUT_SECONDS * 1000 ))
  while :; do
    rss="$(docker exec "$CID" sh -c "ps -o rss= -p 1" 2>/dev/null | tr -d ' ' || true)"
    if [[ -n "$rss" ]]; then
      PEAK_SAMPLES=$((PEAK_SAMPLES + 1))
      if (( rss > PEAK_RSS_KB )); then
        PEAK_RSS_KB="$rss"
      fi
    fi
    # A transient non-2xx/reset from the status endpoint must not abort the whole measurement with
    # a bare curl exit code and no FAIL/STOP line -- the loop's own deadline below already handles
    # "never reaches terminal", so a failed poll here just tries again next tick.
    status="$(crawl_run_status "$RUN_ID" || true)"
    if [[ -n "$status" && "$status" != "RUNNING" ]]; then
      break
    fi
    if [[ "$(now_ms)" -ge "$deadline_ms" ]]; then
      echo "ERROR: crawl run ${RUN_ID} did not reach a terminal status within ${CRAWLER_RUN_TIMEOUT_SECONDS}s." >&2
      exit 1
    fi
    sleep "$CRAWLER_PEAK_SAMPLE_SECONDS"
  done
  PEAK_RSS_MB="$(( PEAK_RSS_KB / 1024 ))"
  echo "  run status: ${status}, ${PEAK_SAMPLES} RSS samples taken"
}

# The measurement is only worth reporting if it measured a real crawl, so the run's own summary
# and the catalogue are both checked.
verify_run_wrote_the_reference_provider() {
  local summary adapter_status adapter_message provider_id tariffs
  summary="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/admin/crawl/runs/${RUN_ID}")"
  adapter_status="$(printf '%s' "$summary" | jq -r '.adapters[0].status // empty')"
  adapter_message="$(printf '%s' "$summary" | jq -r '.adapters[0].message // ""')"
  echo "  adapter result: $(printf '%s' "$summary" | jq -c '.adapters[0] | {adapterId, status, pagesFetched, providersCreated, locationsCreated, tariffsCreated, pricesAppended, pricesUnchanged, failureReason}')"
  if [[ "$adapter_status" != "SUCCESS" ]]; then
    echo "ERROR: the example-static adapter reported ${adapter_status:-<no result>} (${adapter_message}) -- the peak was NOT measured during a real crawl." >&2
    exit 1
  fi
  provider_id="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers?q=example-static" \
    | jq -r '.content[] | select(.slug == "example-static") | .id' | head -n1)"
  if [[ -z "$provider_id" ]]; then
    echo "ERROR: the run reported SUCCESS but provider 'example-static' is not in the catalogue." >&2
    exit 1
  fi
  tariffs="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers/${provider_id}/tariffs?activeOnly=false&size=100" | jq -r '.totalElements')"
  echo "  catalogue: provider 'example-static' id=${provider_id} with ${tariffs} tariffs"
}

profile_crawler() {
  local baseline_rss_mb baseline_rss_kb baseline_cold_ms
  local disabled_rss_mb disabled_delta disabled_cold_ms
  local idle_rss_mb idle_delta idle_cold_ms
  local peak_delta peak_cold_ms

  echo "=== Profile 0/3: baseline, no VDS_CRAWLER_* variable set ==="
  SERVICE_DOCKER_ARGS=()
  measure_idle_profile
  baseline_rss_kb="$PROFILE_RSS_KB"
  baseline_rss_mb="$PROFILE_RSS_MB"
  baseline_cold_ms="$PROFILE_COLD_START_MS"
  echo "Baseline RSS: ${baseline_rss_mb} MB (${baseline_rss_kb} kB)"
  echo

  echo "=== Profile 1/3: VDS_CRAWLER_ENABLED=false (nothing constructed, no thread started) ==="
  SERVICE_DOCKER_ARGS=(-e VDS_CRAWLER_ENABLED=false)
  measure_idle_profile
  disabled_rss_mb="$PROFILE_RSS_MB"
  disabled_cold_ms="$PROFILE_COLD_START_MS"
  disabled_delta=$((disabled_rss_mb - baseline_rss_mb))
  # Printed here as well as in the Results block below, so a profile that is measured survives a
  # later profile aborting the run.
  printf 'Profile 1 RSS: %d MB (%d kB), delta %+d MB vs the %d MB baseline, tolerance +-%s MB\n' \
    "$disabled_rss_mb" "$PROFILE_RSS_KB" "$disabled_delta" "$baseline_rss_mb" "$CRAWLER_DISABLED_TOLERANCE_MB"
  echo

  echo "=== Profile 2/3: VDS_CRAWLER_ENABLED=true, VDS_CRAWLER_ADAPTERS empty (scheduler, no adapter) ==="
  SERVICE_DOCKER_ARGS=(
    -e VDS_CRAWLER_ENABLED=true
    -e VDS_CRAWLER_USERNAME="${CRAWLER_USER}"
    -e VDS_CRAWLER_PASSWORD="${CRAWLER_PASSWORD}"
    -e VDS_CRAWLER_ADAPTERS=
  )
  measure_idle_profile
  idle_rss_mb="$PROFILE_RSS_MB"
  idle_cold_ms="$PROFILE_COLD_START_MS"
  idle_delta=$((idle_rss_mb - baseline_rss_mb))
  printf 'Profile 2 RSS: %d MB (%d kB), delta %+d MB vs the %d MB baseline, limit <= +%s MB\n' \
    "$idle_rss_mb" "$PROFILE_RSS_KB" "$idle_delta" "$baseline_rss_mb" "$CRAWLER_IDLE_MAX_DELTA_MB"
  echo

  echo "=== Profile 3/3: peak during one example-static run over a 1 MiB page ==="
  start_fixture_server
  SERVICE_DOCKER_ARGS=(
    --add-host=host.docker.internal:host-gateway
    -e VDS_CRAWLER_ENABLED=true
    -e VDS_CRAWLER_USERNAME="${CRAWLER_USER}"
    -e VDS_CRAWLER_PASSWORD="${CRAWLER_PASSWORD}"
    -e VDS_CRAWLER_ADAPTERS=example-static
    -e VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL="http://host.docker.internal:${FIXTURE_PORT}"
  )
  start_service
  peak_cold_ms="$COLD_START_MS"
  run_load
  create_service_account
  measure_peak_during_run
  verify_run_wrote_the_reference_provider
  stop_service
  stop_fixture_server
  peak_delta=$((PEAK_RSS_MB - baseline_rss_mb))
  printf 'Profile 3 peak RSS: %d MB (%d kB), delta %+d MB vs the %d MB baseline, limit <= %s MB\n' \
    "$PEAK_RSS_MB" "$PEAK_RSS_KB" "$peak_delta" "$baseline_rss_mb" "$CRAWLER_PEAK_LIMIT_MB"
  echo

  echo "=== Results (RSS inside ${JAVA_IMAGE}, flags: ${JVM_FLAGS[*]}) ==="
  printf 'baseline (no crawler) : RSS %4d MB, cold start %5d ms\n' "$baseline_rss_mb" "$baseline_cold_ms"
  printf 'profile 1 (disabled)  : RSS %4d MB, delta %+d MB vs baseline, limit +-%s MB, cold start %5d ms\n' \
    "$disabled_rss_mb" "$disabled_delta" "$CRAWLER_DISABLED_TOLERANCE_MB" "$disabled_cold_ms"
  printf 'profile 2 (idle, on)  : RSS %4d MB, delta %+d MB vs baseline, limit <= +%s MB, cold start %5d ms\n' \
    "$idle_rss_mb" "$idle_delta" "$CRAWLER_IDLE_MAX_DELTA_MB" "$idle_cold_ms"
  printf 'profile 3 (peak, run) : RSS %4d MB (max of %d samples every %ss), delta %+d MB vs baseline, limit <= %s MB, cold start %5d ms\n' \
    "$PEAK_RSS_MB" "$PEAK_SAMPLES" "$CRAWLER_PEAK_SAMPLE_SECONDS" "$peak_delta" "$CRAWLER_PEAK_LIMIT_MB" "$peak_cold_ms"
  jar_line

  EXIT_CODE=0
  if (( disabled_delta > CRAWLER_DISABLED_TOLERANCE_MB || disabled_delta < -CRAWLER_DISABLED_TOLERANCE_MB )); then
    echo "FAIL: with the crawler disabled RSS is ${disabled_rss_mb}MB, ${disabled_delta}MB off the ${baseline_rss_mb}MB baseline (tolerance +-${CRAWLER_DISABLED_TOLERANCE_MB}MB)." >&2
    EXIT_CODE=1
  fi
  if (( idle_delta > CRAWLER_IDLE_MAX_DELTA_MB )); then
    echo "FAIL: the idle enabled crawler costs ${idle_delta}MB over the ${baseline_rss_mb}MB baseline, above the ${CRAWLER_IDLE_MAX_DELTA_MB}MB allowance." >&2
    EXIT_CODE=1
  fi
  if (( PEAK_RSS_MB > CRAWLER_PEAK_LIMIT_MB )); then
    echo "FAIL: peak RSS during the run is ${PEAK_RSS_MB}MB, above the ${CRAWLER_PEAK_LIMIT_MB}MB limit." >&2
    EXIT_CODE=1
  fi
  if (( EXIT_CODE != 0 )); then
    echo "STOP: a vds-002 footprint limit was exceeded. Do NOT lower a JVM flag, drop a dependency or raise the limit here --" >&2
    echo "      this is the trigger to reopen ADR-01 (in-process crawler) in favour of a cron-launched separate process." >&2
  fi
}

EXIT_CODE=0
SERVICE_DOCKER_ARGS=()
case "$PROFILE" in
  default) profile_default ;;
  crawler) profile_crawler ;;
esac

exit "$EXIT_CODE"
