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
# Profile 3 serves `scripts/footprint-fixture/` -- `vps.html` expanded in memory to exactly 1 MiB
# by repeating its marked filler section (the repository keeps only the few-KB template), plus an
# allowing `robots.txt` -- from a throwaway `python3` HTTP server on the host, which the container reaches
# as `host.docker.internal` (`--add-host=host.docker.internal:host-gateway`, one line that works
# on Docker Desktop and on Linux alike). That server binds every interface, because it has to be
# reachable from inside the container, but only for the duration of the measurement: the cleanup
# trap kills it. `robots.txt` is served rather than switched off with
# VDS_CRAWLER_RESPECT_ROBOTS=false, so the measured run goes through the crawler's real robots
# path. The run writes the `example-static` reference provider into the measured database through
# the admin API, exactly as a production run would; re-running is idempotent (the second run
# creates nothing).
#
# `--enrich` (vds-006) -- site enrichment on real saved provider pages, with the same image, JVM
# flags, dev-seed dataset and 50-request load as `--crawler`, plus the real built SPA (`GET /`,
# `/index.html` and every `/assets/...` file the served HTML references) on every API pass:
#   0. baseline       no VDS_CRAWLER_* variable, same load                 the number below compares to
#   1. peak           max RSS sampled every 200 ms over every enrichment run  <= 300 MB
#   2. leak check     5 consecutive `enrichment=FORCE` runs in one JVM; after each, a 10 s settle,
#                     then RSS; the lowest post-GC heap per run comes from `-Xlog:gc:stdout`
#                     (measurement-only; the JRE image has no jcmd). Run 1 is warm-up. Fails when
#                     RSS after the last run exceeds run 2's by > 15 MB, or the last run's heap floor
#                     exceeds run 2's by > 8 MB with a non-decreasing floor from run 2 on.
# The pages are the prototype capture in ${VDS_ENRICH_REPLAY_DIR:-~/.vds-real-run/enrich-prototype-run6}
# (hostinger-com: 25 pages incl. a 1.6 MB one; nuxt-cloud: an SPA read from its JS chunks), served
# by `scripts/enrich-replay-server.py` on the host. The container keeps each site's own host name
# (`www.hostinger.com.localhost`, mapped to host-gateway), so the analysis sees the real brand.
# Temporary declarative sources, generated per invocation, give both providers an adapter; their
# one-row probe tariff only keeps the adapter (and so the pricing URL) alive. A peak or a leak
# table is reported only when every run enriched both providers with SUCCESS and the catalogue
# holds Hostinger's entity name and at least one nuxt.cloud payment method; otherwise "not measured".
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
#   - `--enrich` only: the replay directory above, and a jar built WITH the frontend
#     (`./gradlew shadowJar`, not `-PskipFrontend`)
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

# --- the --enrich gate (vds-006) ---
ENRICH_PEAK_LIMIT_MB="300"
ENRICH_RUNS="5"
ENRICH_SETTLE_SECONDS="10"
ENRICH_RUN_TIMEOUT_SECONDS="900"
ENRICH_HEAP_GROWTH_LIMIT_MB="8"
ENRICH_RSS_GROWTH_LIMIT_MB="15"
ENRICH_REPLAY_DIR="${VDS_ENRICH_REPLAY_DIR:-$HOME/.vds-real-run/enrich-prototype-run6}"
# slug:pricing path:host port
ENRICH_SITES=("hostinger-com:/vps-hosting:18101" "nuxt-cloud:/ru/virtual-servers:18102")

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
  --enrich)
    PROFILE="enrich"
    ;;
  *)
    echo "usage: $(basename "$0") [--crawler | --enrich]" >&2
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
REPLAY_PIDS=()
ENRICH_TMP=""
EXTRA_JVM_FLAGS=()

# `wait` reaps the killed python3 job inside this redirection, so bash does not print its
# "Terminated: 15 python3 - ... <<PYTHON" job notice -- which would dump the whole heredoc into
# the measurement output.
stop_fixture_server() {
  if [[ -n "$FIXTURE_PID" ]]; then
    { kill "$FIXTURE_PID" && wait "$FIXTURE_PID"; } >/dev/null 2>&1 || true
    FIXTURE_PID=""
  fi
}

stop_replay_servers() {
  local pid
  for pid in ${REPLAY_PIDS[@]+"${REPLAY_PIDS[@]}"}; do
    { kill "$pid" && wait "$pid"; } >/dev/null 2>&1 || true
  done
  REPLAY_PIDS=()
}

cleanup() {
  if [[ -n "$CID" ]]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
  fi
  stop_fixture_server
  stop_replay_servers
  if [[ -n "$ENRICH_TMP" ]]; then
    rm -rf "$ENRICH_TMP"
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
    java "${JVM_FLAGS[@]}" ${EXTRA_JVM_FLAGS[@]+"${EXTRA_JVM_FLAGS[@]}"} -jar /app/app.jar)"

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

# Serves $FIXTURE_DIR to the container: vps.html expanded to 1 MiB for any path, robots.txt for
# /robots.txt.
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
PAGE_SIZE = 1024 * 1024
MARKER = "<!-- filler: the measurement script repeats this section and pads the page to exactly 1 MiB -->\n"

with open(os.path.join(root, "vps.html"), encoding="utf-8") as handle:
    head, rest = handle.read().split(MARKER)
section_end = rest.index("</section>\n") + len("</section>\n")
section, tail = rest[:section_end].encode(), rest[section_end:]
body_end = tail.rindex("</body>")
base = len(head.encode()) + len(tail.encode())
copies = (PAGE_SIZE - base) // len(section)
page = (head.encode() + section * copies + tail[:body_end].encode()
        + b" " * (PAGE_SIZE - base - copies * len(section)) + tail[body_end:].encode())
assert len(page) == PAGE_SIZE
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
      echo "  fixture server: serving ${FIXTURE_DIR} (vps.html expanded to 1 MiB) on port ${FIXTURE_PORT}"
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

# Triggers one run (body $1, default `{"dryRun":false}`) and samples RSS every
# $CRAWLER_PEAK_SAMPLE_SECONDS until the run is terminal or $2 seconds (default
# $CRAWLER_RUN_TIMEOUT_SECONDS) pass. Sets PEAK_RSS_KB, PEAK_RSS_MB, PEAK_SAMPLES and RUN_ID.
measure_peak_during_run() {
  local default_body='{"dryRun":false}'
  local body="${1:-$default_body}" timeout_seconds="${2:-$CRAWLER_RUN_TIMEOUT_SECONDS}"
  local trigger_response rss status deadline_ms
  # An empty-ish body falls back to VDS_CRAWLER_ADAPTERS, i.e. the operator's own selection.
  trigger_response="$(curl -fsS -X POST "${BASE_URL}/api/v1/admin/crawl/run" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$body")"
  RUN_ID="$(printf '%s' "$trigger_response" | jq -r '.runId // empty')"
  if [[ -z "$RUN_ID" ]]; then
    echo "ERROR: POST /api/v1/admin/crawl/run did not return a runId. Response: $trigger_response" >&2
    exit 1
  fi
  echo "  crawl run ${RUN_ID} triggered; sampling RSS every ${CRAWLER_PEAK_SAMPLE_SECONDS}s until it is terminal"

  PEAK_RSS_KB=0
  PEAK_SAMPLES=0
  deadline_ms=$(( $(now_ms) + timeout_seconds * 1000 ))
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
      echo "ERROR: crawl run ${RUN_ID} did not reach a terminal status within ${timeout_seconds}s." >&2
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

# --- --enrich ---------------------------------------------------------------------------------

# Refuses before anything starts: without the real capture there is nothing honest to measure, and
# a jar without the SPA would leave the frontend out of the load.
check_enrich_prerequisites() {
  local site slug
  if [[ ! -f "${ENRICH_REPLAY_DIR}/results.json" ]]; then
    echo "ERROR: no saved prototype run at ${ENRICH_REPLAY_DIR} (results.json missing). Restore the capture or set" >&2
    echo "       VDS_ENRICH_REPLAY_DIR; this profile measures real pages only and has no synthetic fallback." >&2
    exit 1
  fi
  for site in "${ENRICH_SITES[@]}"; do
    slug="${site%%:*}"
    if [[ ! -d "${ENRICH_REPLAY_DIR}/pages/${slug}" ]]; then
      echo "ERROR: ${ENRICH_REPLAY_DIR}/pages/${slug} is missing; this profile measures real pages only." >&2
      exit 1
    fi
  done
  if ! python3 -I -c 'import sys, zipfile; sys.exit(0 if "static/index.html" in zipfile.ZipFile(sys.argv[1]).namelist() else 1)' "$JAR_PATH"; then
    echo "ERROR: ${JAR_PATH} carries no static/index.html -- build it with the frontend ('./gradlew shadowJar', not -PskipFrontend)." >&2
    exit 1
  fi
}

# One replay server per site on its own host port, reachable from the container (host-gateway).
# Sets REPLAY_PIDS and ENRICH_HOSTS (one `--add-host` argument pair per site) and fills
# ENRICH_ORIGINS[slug-index] with the origin each server serves.
start_replay_servers() {
  local site slug port log origin deadline_ms
  ENRICH_HOSTS=()
  ENRICH_ORIGINS=()
  for site in "${ENRICH_SITES[@]}"; do
    slug="${site%%:*}"
    port="${site##*:}"
    log="${ENRICH_TMP}/replay-${slug}.log"
    python3 -I scripts/enrich-replay-server.py --run-dir "$ENRICH_REPLAY_DIR" --slug "$slug" --port "$port" --listen-all \
      >"$log" 2>&1 &
    REPLAY_PIDS+=($!)
    origin=""
    deadline_ms=$(( $(now_ms) + 10000 ))
    while [[ "$(now_ms)" -lt "$deadline_ms" ]]; do
      origin="$(sed -n 's/^replaying .* at \(http:[^ ]*\)$/\1/p' "$log" | head -n1)"
      if [[ -n "$origin" && "$(curl -fsS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/robots.txt" 2>/dev/null || true)" == "200" ]]; then
        break
      fi
      origin=""
      sleep 0.1
    done
    if [[ -z "$origin" ]]; then
      echo "ERROR: the replay server for ${slug} did not come up on port ${port}:" >&2
      cat "$log" >&2
      exit 1
    fi
    ENRICH_ORIGINS+=("$origin")
    ENRICH_HOSTS+=(--add-host="$(printf '%s' "$origin" | sed 's|^http://\([^:/]*\).*|\1|'):host-gateway")
    echo "  replay: ${slug} at ${origin} (host port ${port})"
  done
}

# Declarative sources pointing at the replay origins. The probe tariff reads a number out of the
# viewport meta that both captured pricing pages carry; it only keeps the adapter alive.
write_enrich_sources() {
  local i site slug path
  mkdir -p "${ENRICH_TMP}/sources"
  for i in "${!ENRICH_SITES[@]}"; do
    site="${ENRICH_SITES[$i]}"
    slug="${site%%:*}"
    path="${site#*:}"
    path="${path%:*}"
    python3 -I - "${ENRICH_TMP}/sources/${slug}.json" "$slug" "${ENRICH_ORIGINS[$i]}" "$path" <<'PYTHON'
import json, sys
target, slug, origin, path = sys.argv[1:]
probe = {"value": "probe"}
source = {
    "schemaVersion": 1, "id": slug, "providerSlug": slug, "acknowledgeSlugMismatch": True,
    "baseUrl": origin, "pagePath": path, "enabled": True,
    "provider": {"name": {"value": slug}, "websiteUrl": {"value": origin + "/"}},
    "tariffs": {
        "rootSelector": "html", "rowSelector": "head", "externalId": probe, "name": probe,
        "vcpuCount": {"value": "1"}, "ramMb": {"value": "1024"}, "diskGb": {"value": "10"},
        "ipv4Count": {"value": "1"}, "diskType": {"value": "SSD"}, "virtualization": {"value": "KVM"},
        "prices": [{"billingPeriod": "MONTHLY", "selector": "meta[name=viewport]", "attr": "content",
                    "currency": {"fixed": "EUR"}}],
    },
}
with open(target, "w", encoding="utf-8") as handle:
    json.dump(source, handle)
PYTHON
  done
}

# Each replayed provider must exist before the run; 409 means an earlier invocation created it.
create_enrich_providers() {
  local i slug code
  for i in "${!ENRICH_SITES[@]}"; do
    slug="${ENRICH_SITES[$i]%%:*}"
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/providers" \
      -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
      -d "{\"slug\":\"${slug}\",\"name\":\"${slug}\",\"websiteUrl\":\"${ENRICH_ORIGINS[$i]}/\",\"status\":\"ACTIVE\"}")"
    if [[ "$code" != "201" && "$code" != "409" ]]; then
      echo "ERROR: creating provider ${slug} returned ${code}." >&2
      exit 1
    fi
  done
}

# The built SPA as a browser loads it: `/`, `/index.html` and every `/assets/...` file the served
# HTML references, read from the HTML itself so a new build needs no edit here. Run 20 times, like
# the API passes. Every request must return 200.
run_frontend_load() {
  local html assets asset i code ok=0 total=0
  html="$(curl -fsS "${BASE_URL}/")"
  assets=($(printf '%s' "$html" | python3 -I -c 'import re, sys; print("\n".join(dict.fromkeys(re.findall(r"(?:src|href)=\"(/assets/[^\"]+)\"", sys.stdin.read()))))'))
  if [[ "${#assets[@]}" -eq 0 ]]; then
    echo "ERROR: the served index.html references no /assets/ file -- the SPA is not part of this jar's load." >&2
    exit 1
  fi
  for i in $(seq 1 20); do
    for asset in / /index.html "${assets[@]}"; do
      code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}${asset}")"
      total=$((total + 1))
      [[ "$code" == "200" ]] && ok=$((ok + 1))
    done
  done
  echo "Frontend load: ${total} requests (20 x /, /index.html and ${#assets[@]} assets), ${ok}/${total} returned 200"
  if [[ "$ok" -ne "$total" ]]; then
    echo "ERROR: not every frontend request returned 200." >&2
    exit 1
  fi
}

# Exits "not measured" unless both replayed providers were enriched with SUCCESS in run $RUN_ID.
verify_enrichment_run() {
  local summary outcomes
  summary="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/admin/crawl/runs/${RUN_ID}")"
  outcomes="$(printf '%s' "$summary" | jq -r '[.adapters[] | "\(.adapterId)=\(.enrichment.outcome // "none")"] | join(" ")')"
  RUN_OUTCOMES="$outcomes"
  local i slug
  for i in "${!ENRICH_SITES[@]}"; do
    slug="${ENRICH_SITES[$i]%%:*}"
    if [[ " $outcomes " != *" ${slug}=SUCCESS "* ]]; then
      echo "ERROR: run ${RUN_ID} did not enrich ${slug} with SUCCESS (${outcomes}): $(printf '%s' "$summary" | jq -c '[.adapters[] | {adapterId, status, enrichment}]')" >&2
      echo "NOT MEASURED: a number is reported only for a run that enriched every replayed provider." >&2
      exit 1
    fi
  done
}

# The values the replay must produce, read back from the catalogue.
verify_enrichment_values() {
  local id entity methods
  id="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers?q=hostinger-com" | jq -r '.content[] | select(.slug == "hostinger-com") | .id')"
  entity="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers/${id}" | jq -r '.legalInfo.legalEntityName // empty')"
  id="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers?q=nuxt-cloud" | jq -r '.content[] | select(.slug == "nuxt-cloud") | .id')"
  methods="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers/${id}" | jq -r '[.paymentMethods[].method] | join(",")')"
  echo "  catalogue: hostinger-com entity='${entity}', nuxt-cloud payment methods=${methods:-none}"
  if [[ -z "$entity" || -z "$methods" ]]; then
    echo "NOT MEASURED: the catalogue lacks the expected enrichment values (Hostinger entity, nuxt.cloud payment methods)." >&2
    exit 1
  fi
}

# Lowest post-GC heap occupancy (MB, one decimal) in container log lines after line $1, or empty.
post_gc_floor_mb() {
  docker logs "$CID" 2>&1 | tail -n +"$(( $1 + 1 ))" | python3 -I -c '
import re, sys
units = {"K": 1 / 1024, "M": 1, "G": 1024}
floors = [int(m.group(1)) * units[m.group(2)]
          for m in (re.search(r"\[gc\s*\].*GC\(\d+\) Pause .*?->(\d+)([KMG])\(", line) for line in sys.stdin) if m]
print(f"{min(floors):.1f}" if floors else "")'
}

profile_enrich() {
  local baseline_rss_mb baseline_cold_ms enrich_cold_ms run log_start floor rss_after
  local -a floors=() rss_values=() peaks=() outcomes=() durations=()
  local overall_peak_kb=0 start_ms

  check_enrich_prerequisites
  ENRICH_TMP="$(mktemp -d)"

  echo "=== Baseline: no VDS_CRAWLER_* variable, API + frontend load ==="
  SERVICE_DOCKER_ARGS=()
  start_service
  baseline_cold_ms="$COLD_START_MS"
  run_load
  run_frontend_load
  baseline_rss_mb="$(( $(read_rss_kb) / 1024 ))"
  stop_service
  echo "Baseline RSS: ${baseline_rss_mb} MB"
  echo

  echo "=== Enrichment: ${ENRICH_RUNS} consecutive FORCE runs over real saved pages in one JVM ==="
  start_replay_servers
  write_enrich_sources
  local adapters="" site
  for site in "${ENRICH_SITES[@]}"; do
    adapters+="${adapters:+,}${site%%:*}"
  done
  SERVICE_DOCKER_ARGS=(
    ${ENRICH_HOSTS[@]+"${ENRICH_HOSTS[@]}"}
    -v "${ENRICH_TMP}/sources:/sources:ro"
    -e VDS_DISCOVERY_SOURCES_DIR=/sources
    -e VDS_CRAWLER_ENABLED=true
    -e VDS_CRAWLER_USERNAME="${CRAWLER_USER}"
    -e VDS_CRAWLER_PASSWORD="${CRAWLER_PASSWORD}"
    -e VDS_CRAWLER_ADAPTERS="${adapters}"
  )
  EXTRA_JVM_FLAGS=(-Xlog:gc:stdout)
  start_service
  enrich_cold_ms="$COLD_START_MS"
  run_load
  run_frontend_load
  create_service_account
  create_enrich_providers

  for run in $(seq 1 "$ENRICH_RUNS"); do
    log_start="$(docker logs "$CID" 2>&1 | wc -l | tr -d ' ')"
    start_ms="$(now_ms)"
    measure_peak_during_run '{"dryRun":false,"enrichment":"FORCE"}' "$ENRICH_RUN_TIMEOUT_SECONDS"
    durations+=("$(( ($(now_ms) - start_ms) / 1000 ))")
    verify_enrichment_run
    outcomes+=("$RUN_OUTCOMES")
    [[ "$run" -eq 1 ]] && verify_enrichment_values
    (( PEAK_RSS_KB > overall_peak_kb )) && overall_peak_kb="$PEAK_RSS_KB"
    peaks+=("$PEAK_RSS_MB")
    run_load
    run_frontend_load
    sleep "$ENRICH_SETTLE_SECONDS"
    rss_after="$(( $(read_rss_kb) / 1024 ))"
    floor="$(post_gc_floor_mb "$log_start")"
    if [[ -z "$floor" ]]; then
      echo "NOT MEASURED: no GC was logged during run ${run}, so its heap floor is unknown." >&2
      exit 1
    fi
    floors+=("$floor")
    rss_values+=("$rss_after")
    printf '  run %d: peak %d MB, RSS after settle %d MB, post-GC heap floor %s MB, %s\n' \
      "$run" "$PEAK_RSS_MB" "$rss_after" "$floor" "$RUN_OUTCOMES"
  done
  stop_service
  stop_replay_servers

  local peak_mb=$(( overall_peak_kb / 1024 )) last=$(( ENRICH_RUNS - 1 ))
  local heap_growth rss_growth heap_trend
  heap_growth="$(python3 -I -c "print(f'{${floors[$last]} - ${floors[1]}:.1f}')")"
  rss_growth=$(( rss_values[last] - rss_values[1] ))
  heap_trend="$(python3 -I -c "
f = [$(IFS=,; echo "${floors[*]}")][1:]
print('non-decreasing' if all(b >= a for a, b in zip(f, f[1:])) else 'not monotonic')")"

  echo
  echo "=== Results (RSS inside ${JAVA_IMAGE}, flags: ${JVM_FLAGS[*]} + ${EXTRA_JVM_FLAGS[*]} for the enrichment JVM) ==="
  printf 'baseline (API + SPA load) : RSS %4d MB, cold start %5d ms\n' "$baseline_rss_mb" "$baseline_cold_ms"
  printf 'enrichment JVM            : cold start %5d ms\n' "$enrich_cold_ms"
  printf 'peak over %d runs          : RSS %4d MB (sampled every %ss), delta %+d MB vs baseline, limit <= %s MB\n' \
    "$ENRICH_RUNS" "$peak_mb" "$CRAWLER_PEAK_SAMPLE_SECONDS" "$(( peak_mb - baseline_rss_mb ))" "$ENRICH_PEAK_LIMIT_MB"
  echo "leak table (run 1 is warm-up):"
  printf '  %-4s %8s %14s %16s %9s\n' run "peak MB" "RSS settled MB" "post-GC floor MB" "seconds"
  for run in $(seq 0 "$last"); do
    printf '  %-4d %8d %14d %16s %9d\n' "$((run + 1))" "${peaks[$run]}" "${rss_values[$run]}" "${floors[$run]}" "${durations[$run]}"
  done
  printf 'heap floor run %d vs run 2: %+.1f MB (%s), limit +%s MB with a non-decreasing floor\n' \
    "$ENRICH_RUNS" "$heap_growth" "$heap_trend" "$ENRICH_HEAP_GROWTH_LIMIT_MB"
  printf 'RSS run %d vs run 2: %+d MB, limit +%s MB\n' "$ENRICH_RUNS" "$rss_growth" "$ENRICH_RSS_GROWTH_LIMIT_MB"
  jar_line

  EXIT_CODE=0
  if (( peak_mb > ENRICH_PEAK_LIMIT_MB )); then
    echo "FAIL: peak RSS during enrichment is ${peak_mb}MB, above the ${ENRICH_PEAK_LIMIT_MB}MB limit." >&2
    EXIT_CODE=1
  fi
  if [[ "$heap_trend" == "non-decreasing" ]] && python3 -I -c "import sys; sys.exit(0 if ${heap_growth} > ${ENRICH_HEAP_GROWTH_LIMIT_MB} else 1)"; then
    echo "FAIL: the post-GC heap floor grew ${heap_growth}MB from run 2 to run ${ENRICH_RUNS} and never went down: a leak." >&2
    EXIT_CODE=1
  fi
  if (( rss_growth > ENRICH_RSS_GROWTH_LIMIT_MB )); then
    echo "FAIL: RSS after run ${ENRICH_RUNS} is ${rss_growth}MB above RSS after run 2." >&2
    EXIT_CODE=1
  fi
  if (( EXIT_CODE != 0 )); then
    echo "STOP: an enrichment footprint limit was exceeded. Do NOT change JVM flags, limits, load or pages here --" >&2
    echo "      report it; ADR vds-002-01 (in-process crawler) is reopened in favour of a separate process." >&2
  fi
}

EXIT_CODE=0
SERVICE_DOCKER_ARGS=()
case "$PROFILE" in
  default) profile_default ;;
  crawler) profile_crawler ;;
  enrich) profile_enrich ;;
esac

exit "$EXIT_CODE"
