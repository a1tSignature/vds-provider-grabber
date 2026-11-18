#!/usr/bin/env bash
# Measure cold start and Linux-container RSS under a fixed load. See README §6 for setup, profile thresholds and output.
# The crawler peak uses a local HTML and robots fixture reached from Docker via host-gateway.
# The enrichment profile requires saved pages and a built SPA. It tries one JVM first, then one JVM per batch if the drain fails the memory gate.
# VDS_CRAWLER_RESPECT_ROBOTS=false, so the measured run goes through the crawler's real robots
# path. The run writes the `example-static` reference provider into the measured database through
# the admin API, exactly as a production run would; re-running is idempotent (the second run
# creates nothing).
#
# `--enrich` (vds-007) -- the site enrichment PROCESS next to the service: two JVMs with the same
# image and flags, on real saved provider pages, under the same API + built-SPA load:
#   0. baseline       the service alone, no VDS_CRAWLER_*/VDS_ENRICH_*, API + SPA load
#   A. drain          one process container (scripts/enrich-launch.sh, VDS_ENRICH_BATCHES_PER_JVM=0)
#                     enriches 50 gate providers = 5 batches of 10 in ONE JVM. Sampled every 200 ms:
#                     process RSS and service RSS (`ps` inside each container), PostgreSQL as the
#                     sum of Pss over its processes (/proc/<pid>/smaps_rollup, every 1 s, read as the
#                     postgres user). Passes when RSS(last batch) - RSS(batch 2) <= 15 MB, the
#                     post-Full-GC heap grows <= 8 MB from batch 2 (batches without a Full GC are left
#                     out) and service peak + process peak + PostgreSQL peak <= 700 MB. The service
#                     peak is reported against 300 MB but does not decide (owner, 2026-11-15).
#   B. per-batch      only when A fails: 50 fresh providers, one batch per JVM, relaunched by the
#                     launcher; passes when the sum with the largest per-JVM peak <= 700 MB.
# The last line is `MODE: drain` or `MODE: per-batch` (exit 0), or `STOP` (non-zero); the stand's
# VDS_ENRICH_BATCHES_PER_JVM follows it. Gate sites: the 12 captured sites of
# ${VDS_ENRICH_REPLAY_DIR:-~/.vds-real-run/enrich-prototype-run6} that have a page file (fornex-com
# has none), each served by `scripts/enrich-replay-server.py` on its own host port. Every batch holds
# hostinger-com (the 1.6 MB page), nuxt-cloud (SPA) and 8 of the other 10 in rotation. The gate
# providers are CANDIDATEs, so the default listing and the load are unchanged; every other provider
# that would be due is pre-marked NOTHING_FOUND first, so the queue is exactly the gate. Nothing is
# reported ("NOT MEASURED", non-zero) unless every gate provider ended SUCCESS or NOTHING_FOUND
# after the mode started (a replayed site that is unreachable is a broken measurement), Hostinger
# copies carry an entity, nuxt.cloud copies a payment method, and no pre-marked provider was
# touched. Expect roughly 25-50 minutes per mode.
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

CRAWLER_DISABLED_TOLERANCE_MB="3"
CRAWLER_IDLE_MAX_DELTA_MB="15"
CRAWLER_PEAK_LIMIT_MB="300"
CRAWLER_PEAK_SAMPLE_SECONDS="0.2"
CRAWLER_RUN_TIMEOUT_SECONDS="120"
FIXTURE_DIR="scripts/footprint-fixture"
FIXTURE_PORT="18099"
# Reuse the service account between measurements. The create request may return 409 on a rerun.
CRAWLER_USER="crawler-footprint"
CRAWLER_PASSWORD="Footprint-Crawler-2026!"

# Report the service peak separately. The combined peak is the memory gate.
ENRICH_SUM_LIMIT_MB="700"
ENRICH_HEAP_GROWTH_LIMIT_MB="8"
ENRICH_RSS_GROWTH_LIMIT_MB="15"
ENRICH_SERVICE_PEAK_LIMIT_MB="300"
ENRICH_GATE_PROVIDERS="50"
ENRICH_BATCH_SIZE="10"
ENRICH_PG_SAMPLE_SECONDS="1"
ENRICH_SETTLE_SECONDS="10"
ENRICH_MODE_TIMEOUT_SECONDS="7200"
ENRICH_SERVICE_ALIAS="vds-gate-app"
ENRICH_REPLAY_DIR="${VDS_ENRICH_REPLAY_DIR:-$HOME/.vds-real-run/enrich-prototype-run6}"
# In every batch; the other gate sites rotate through the remaining 8 slots.
ENRICH_FIXED_SITES=(hostinger-com nuxt-cloud)
ENRICH_ROTATING_SITES=(aeza-net arct-cloud buyvm-net digitalocean-com host-co-in lunanode-com ovhcloud-com racknerd-com servercheap-com vdsina-com)
ENRICH_FIRST_PORT="18101"

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
ECID=""
FIXTURE_PID=""
REPLAY_PIDS=()
ENRICH_TMP=""
EXTRA_JVM_FLAGS=()

# Reap the fixture job under redirection to keep Bash from dumping its heredoc in the job notice.
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
  if [[ -n "$ECID" ]]; then
    docker rm -f "$ECID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$CID" ]]; then
    delete_gate_providers
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
  # Use Python for portable millisecond timestamps, with whole seconds as a fallback.
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(int(time.time() * 1000))'
  else
    echo "$((SECONDS * 1000))"
  fi
}

BASE_URL="http://localhost:${HOST_PORT}"


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

  local total_requests=$((TOTAL_CATALOGUE + 1))
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


# Preserve the default profile's output format.
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

# Serve the extensionless /vps path as HTML. Python's default file server would send application/octet-stream, which the crawler rejects.
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
    # Retry transient status failures until the polling deadline.
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
  # Print each result as it finishes so a later profile failure cannot hide it.
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


# "NOT MEASURED": the run was not the run the numbers would describe. Never prints a number.
not_measured() {
  echo "NOT MEASURED: $*" >&2
  exit 1
}

# Refuses before anything starts: without the real capture there is nothing honest to measure, and
# a jar without the SPA would leave the frontend out of the load.
check_enrich_prerequisites() {
  local slug
  if [[ ! -f "${ENRICH_REPLAY_DIR}/results.json" ]]; then
    echo "ERROR: no saved prototype run at ${ENRICH_REPLAY_DIR} (results.json missing). Restore the capture or set" >&2
    echo "       VDS_ENRICH_REPLAY_DIR; this profile measures real pages only and has no synthetic fallback." >&2
    exit 1
  fi
  for slug in "${ENRICH_FIXED_SITES[@]}" "${ENRICH_ROTATING_SITES[@]}"; do
    if [[ ! -d "${ENRICH_REPLAY_DIR}/pages/${slug}" ]]; then
      echo "ERROR: ${ENRICH_REPLAY_DIR}/pages/${slug} is missing; this profile measures real pages only." >&2
      exit 1
    fi
  done
  if ! python3 -I -c 'import sys, zipfile; sys.exit(0 if "static/index.html" in zipfile.ZipFile(sys.argv[1]).namelist() else 1)' "$JAR_PATH"; then
    echo "ERROR: ${JAR_PATH} carries no static/index.html -- build it with the frontend ('./gradlew shadowJar', not -PskipFrontend)." >&2
    exit 1
  fi
  if [[ ! -f scripts/enrich-launch.sh ]]; then
    echo "ERROR: scripts/enrich-launch.sh is missing; the process is measured only through its launcher." >&2
    exit 1
  fi
}

# Read asset URLs from the served HTML so each build is exercised without maintaining a separate asset list.
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

# One replay server per gate site on its own host port, reachable from the containers through
# host-gateway. Fills SITE_ORIGIN[slug], SITE_PAGE_PATH[slug] (the path of results.json pages[1],
# the captured pricing page) and ENRICH_HOSTS (one --add-host per origin).
start_replay_servers() {
  local slug port log origin deadline_ms
  ENRICH_HOSTS=()
  port="$ENRICH_FIRST_PORT"
  for slug in "${ENRICH_FIXED_SITES[@]}" "${ENRICH_ROTATING_SITES[@]}"; do
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
    printf '%s\t%s\t%s\n' "$slug" "$origin" "$(python3 -I -c '
import json, sys, urllib.parse
u = urllib.parse.urlsplit(json.load(open(sys.argv[1]))[sys.argv[2]]["pages"][1]["url"])
print((u.path or "/") + ("?" + u.query if u.query else ""))' "${ENRICH_REPLAY_DIR}/results.json" "$slug")" >>"${ENRICH_TMP}/sites.tsv"
    ENRICH_HOSTS+=(--add-host="$(printf '%s' "$origin" | sed 's|^http://\([^:/]*\).*|\1|'):host-gateway")
    echo "  replay: ${slug} at ${origin} (host port ${port})"
    port=$((port + 1))
  done
}

# Creates $ENRICH_GATE_PROVIDERS CANDIDATE providers in batch order (the queue orders
# never-attempted providers by id) and one enabled source file per provider. Each batch of
# $ENRICH_BATCH_SIZE holds 10 different sites: the fixed ones plus 8 rotating ones.
# Appends "id<TAB>slug<TAB>site" to $1 and to ${ENRICH_TMP}/created.tsv (the cleanup list).
create_gate_providers() {
  local out="$1" sources="$2" suffix n=0 batch k slug site origin path code body id
  local per_batch_rotating=$(( ENRICH_BATCH_SIZE - ${#ENRICH_FIXED_SITES[@]} ))
  suffix="g$(date +%s)"
  rm -rf "$sources"
  mkdir -p "$sources"
  : >"$out"
  for batch in $(seq 0 $(( ENRICH_GATE_PROVIDERS / ENRICH_BATCH_SIZE - 1 ))); do
    local -a sites=("${ENRICH_FIXED_SITES[@]}")
    for k in $(seq 0 $(( per_batch_rotating - 1 ))); do
      sites+=("${ENRICH_ROTATING_SITES[$(( (batch * 2 + k) % ${#ENRICH_ROTATING_SITES[@]} ))]}")
    done
    for site in "${sites[@]}"; do
      n=$((n + 1))
      slug="${site}-${suffix}-${n}"
      origin="$(awk -F'\t' -v s="$site" '$1 == s {print $2}' "${ENRICH_TMP}/sites.tsv")"
      path="$(awk -F'\t' -v s="$site" '$1 == s {print $3}' "${ENRICH_TMP}/sites.tsv")"
      body="{\"slug\":\"${slug}\",\"name\":\"${slug}\",\"websiteUrl\":\"${origin}/\",\"status\":\"UNKNOWN\",\"systemStatus\":\"CANDIDATE\"}"
      code="$(curl -s -o "${ENRICH_TMP}/create.json" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/providers" \
        -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -d "$body")"
      if [[ "$code" != "201" ]]; then
        echo "ERROR: creating gate provider ${slug} returned ${code}: $(cat "${ENRICH_TMP}/create.json")" >&2
        exit 1
      fi
      id="$(jq -r '.id' "${ENRICH_TMP}/create.json")"
      printf '%s\t%s\t%s\n' "$id" "$slug" "$site" | tee -a "${ENRICH_TMP}/created.tsv" >>"$out"
      python3 -I - "${sources}/${slug}.json" "$slug" "$origin" "$path" <<'PYTHON'
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
  done
  echo "  gate providers: ${n} CANDIDATEs (${suffix}), ${ENRICH_GATE_PROVIDERS}/${ENRICH_BATCH_SIZE} batches in id order"
}

# id<TAB>lastAttemptAt (or "-") of every CONFIGURED/CANDIDATE provider, into $1.
list_queue_members() {
  local page=0 pages=1
  : >"$1"
  while (( page < pages )); do
    curl -fsS -H "Authorization: Bearer ${TOKEN}" \
      "${BASE_URL}/api/v1/providers?systemStatus=CONFIGURED&systemStatus=CANDIDATE&sort=id,asc&size=100&page=${page}" \
      >"${ENRICH_TMP}/page.json"
    jq -r '.content[] | "\(.id)\t\(.enrichmentLastAttemptAt // "-")"' "${ENRICH_TMP}/page.json" >>"$1"
    pages="$(jq -r '.totalPages' "${ENRICH_TMP}/page.json")"
    page=$((page + 1))
  done
}

# Record fresh attempts for non-gate providers so the process queue contains only gate fixtures.
premark_other_providers() {
  local gate="$1" id last marked=0 code
  list_queue_members "${ENRICH_TMP}/members.tsv"
  while IFS=$'\t' read -r id last; do
    grep -q "^${id}"$'\t' "$gate" && continue
    if [[ "$last" == "-" ]] || python3 -I -c '
import sys, datetime
t = datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
sys.exit(0 if datetime.datetime.now(datetime.timezone.utc) - t >= datetime.timedelta(hours=720) else 1)' "$last"; then
      code="$(curl -s -o "${ENRICH_TMP}/mark.json" -w '%{http_code}' -X PUT "${BASE_URL}/api/v1/providers/${id}/enrichment" \
        -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
        -d '{"outcome":"NOTHING_FOUND","message":"pre-marked by measure-footprint.sh --enrich","pagesFetched":0}')"
      if [[ "$code" != "200" ]]; then
        echo "ERROR: pre-marking provider ${id} returned ${code}: $(cat "${ENRICH_TMP}/mark.json")" >&2
        exit 1
      fi
      marked=$((marked + 1))
    fi
  done <"${ENRICH_TMP}/members.tsv"
  echo "  pre-marked ${marked} other provider(s) NOTHING_FOUND, so the queue is exactly the gate"
  list_queue_members "${ENRICH_TMP}/members.tsv"
  awk -F'\t' 'NR == FNR {gate[$1] = 1; next} !($1 in gate)' "$gate" "${ENRICH_TMP}/members.tsv" >"${ENRICH_TMP}/others.tsv"
}

# Starts the process container through the launcher with VDS_ENRICH_BATCHES_PER_JVM=$1. Sets ECID.
start_process() {
  ECID="$(docker run -d \
    --network "$NETWORK" \
    ${ENRICH_HOSTS[@]+"${ENRICH_HOSTS[@]}"} \
    -v "$(pwd)/${JAR_PATH}:/app/app.jar:ro" \
    -v "$(pwd)/scripts/enrich-launch.sh:/app/enrich-launch.sh:ro" \
    -v "${ENRICH_TMP}/sources:/sources:ro" \
    -e VDS_ENRICH_API_BASE_URL="http://${ENRICH_SERVICE_ALIAS}:8080" \
    -e VDS_ENRICH_USERNAME="${CRAWLER_USER}" \
    -e VDS_ENRICH_PASSWORD="${CRAWLER_PASSWORD}" \
    -e VDS_ENRICH_BATCHES_PER_JVM="$1" \
    -e VDS_ENRICH_EXTRA_JVM_FLAGS=-Xlog:gc:stdout \
    -e VDS_ENRICH_LOCK_FILE=/tmp/vds-enrich.lock \
    -e VDS_ENRICH_JAR=/app/app.jar \
    -e VDS_DISCOVERY_SOURCES_DIR=/sources \
    "$JAVA_IMAGE" bash /app/enrich-launch.sh)"
}

# Samples until the process container exits: every 200 ms process RSS (all `java` processes in it)
# and service RSS; every ${ENRICH_PG_SAMPLE_SECONDS} s PostgreSQL Pss and the process log, whose
# batch and JVM-exit lines tag each sample with the batch / JVM it belongs to. Writes
# "t_ms proc_kb svc_kb pg_kb batches_done jvms_done" lines to $1; exits non-zero if Pss is unreadable.
sample_until_process_exits() {
  python3 -I - "$1" "$ECID" "$CID" "$POSTGRES_CID" "$CRAWLER_PEAK_SAMPLE_SECONDS" "$ENRICH_PG_SAMPLE_SECONDS" "$ENRICH_MODE_TIMEOUT_SECONDS" <<'PYTHON'
import subprocess, sys, time
out, proc, svc, pg, step, pg_step, timeout = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], float(sys.argv[5]), float(sys.argv[6]), float(sys.argv[7])
PG_PSS = ('t=0; r=0; s=0; for d in /proc/[0-9]*; do [ "$(cat $d/comm 2>/dev/null)" = postgres ] || continue; '
          't=$((t+1)); v=$(awk \'/^Pss:/{print $2}\' $d/smaps_rollup 2>/dev/null); '
          '[ -n "$v" ] && { r=$((r+1)); s=$((s+v)); }; done; echo "$t $r $s"')
def run(args):
    return subprocess.run(args, capture_output=True, text=True)
def running():
    return run(["docker", "inspect", "-f", "{{.State.Running}}", proc]).stdout.strip() == "true"
started, last_pg, pg_kb, batches, jvms = time.time(), 0.0, "", 0, 0
with open(out, "w") as fh:
    while running():
        if time.time() - started > timeout:
            print(f"ERROR: the process did not finish within {int(timeout)} s", file=sys.stderr); sys.exit(2)
        p = subprocess.Popen(["docker", "exec", proc, "ps", "-eo", "rss=,comm="], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        q = subprocess.Popen(["docker", "exec", svc, "ps", "-o", "rss=", "-p", "1"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        proc_kb = sum(int(r.split()[0]) for r in p.communicate()[0].splitlines() if r.split()[1:] == ["java"])
        svc_out = q.communicate()[0].strip()
        if time.time() - last_pg >= pg_step:
            total, readable, pss = (run(["docker", "exec", "-u", "postgres", pg, "sh", "-c", PG_PSS]).stdout.split() + ["0", "0", "0"])[:3]
            if int(total) == 0 or readable != total:
                print(f"ERROR: PostgreSQL Pss unreadable ({readable} of {total} processes)", file=sys.stderr); sys.exit(3)
            pg_kb, last_pg = pss, time.time()
            log = run(["docker", "logs", proc]).stdout
            batches, jvms = log.count(" enrich batch "), log.count("enrich-launch: jvm ")
        if proc_kb and svc_out:
            fh.write(f"{int(time.time() * 1000)} {proc_kb} {svc_out} {pg_kb or 0} {batches} {jvms}\n"); fh.flush()
        time.sleep(step)
PYTHON
}

# Post-Full-GC heap occupancy (MB) per batch from the process log: the lowest after-value of the
# `Pause Full` lines logged before that batch's `enrich batch` line; "-" when the batch had none.
# Writes "batch providers rssMb durationMs fullgc" lines to $1.
parse_batches() {
  docker logs "$ECID" 2>&1 | python3 -I -c '
import re, sys
units = {"K": 1 / 1024, "M": 1, "G": 1024}
full, rows = [], []
for line in sys.stdin:
    m = re.search(r"\[gc\s*\].*GC\(\d+\) Pause Full .*?->(\d+)([KMG])\(", line)
    if m:
        full.append(int(m.group(1)) * units[m.group(2)]); continue
    b = re.search(r" enrich batch \d+ providers=(\d+) .*durationMs=(\d+)(?: rssMb=(\d+))?", line)
    if b:
        rows.append((b.group(1), b.group(3) or "-", b.group(2), f"{min(full):.1f}" if full else "-")); full = []
for i, r in enumerate(rows, 1):
    print(i, *r)' >"$1"
}

# The JVM exit codes the launcher logged, one per line, into $1.
parse_jvm_exits() {
  docker logs "$ECID" 2>&1 | sed -n 's/.*enrich-launch: jvm [0-9]* exited \([0-9]*\)$/\1/p' >"$1"
}

# Every validity rule of a mode; any failure is "NOT MEASURED". $1 = gate list, $2 = mode start
# (epoch seconds), $3 = drain|per-batch.
validate_mode() {
  local gate="$1" since="$2" mode="$3" exit_code id slug site detail last outcome
  exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$ECID")"
  [[ "$exit_code" == "0" ]] || not_measured "the launcher exited ${exit_code}: $(docker logs "$ECID" 2>&1 | grep -E 'enrich-launch|launch finished' | tail -n 3 | tr '\n' ' ')"
  parse_jvm_exits "${ENRICH_TMP}/jvm-exits"
  [[ "$(tail -n 1 "${ENRICH_TMP}/jvm-exits")" == "0" ]] || not_measured "the last JVM did not exit 0"
  if [[ "$mode" == "drain" ]]; then
    [[ "$(wc -l <"${ENRICH_TMP}/jvm-exits" | tr -d ' ')" == "1" ]] || not_measured "drain mode used more than one JVM"
  else
    (( $(wc -l <"${ENRICH_TMP}/jvm-exits") >= 5 )) || not_measured "per-batch mode ran fewer than 5 JVMs"
    [[ -z "$(sed '$d' "${ENRICH_TMP}/jvm-exits" | grep -v '^10$')" ]] || not_measured "a per-batch JVM other than the last exited with a code other than 10"
  fi
  (( $(awk -v n="$ENRICH_BATCH_SIZE" '$2 == n' "${ENRICH_TMP}/batches" | wc -l) >= 5 )) || not_measured "fewer than 5 batches of ${ENRICH_BATCH_SIZE} were logged"
  while IFS=$'\t' read -r id slug site; do
    detail="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers/${id}")"
    last="$(printf '%s' "$detail" | jq -r '.enrichment.lastAttemptAt // empty')"
    outcome="$(printf '%s' "$detail" | jq -r '.enrichment.lastOutcome // empty')"
    [[ -n "$last" ]] || not_measured "gate provider ${slug} was never attempted"
    python3 -I -c 'import sys, datetime; sys.exit(0 if datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00")).timestamp() >= int(sys.argv[2]) else 1)' "$last" "$since" \
      || not_measured "gate provider ${slug} was last attempted at ${last}, before this mode started"
    [[ "$outcome" == "SUCCESS" || "$outcome" == "NOTHING_FOUND" ]] || not_measured "gate provider ${slug} ended ${outcome}: a replayed site must be read, not ${outcome}"
    if [[ "$site" == "hostinger-com" && -z "$(printf '%s' "$detail" | jq -r '.legalInfo.legalEntityName // empty')" ]]; then
      not_measured "${slug} has no legal entity name"
    fi
    if [[ "$site" == "nuxt-cloud" && "$(printf '%s' "$detail" | jq -r '.paymentMethods | length')" == "0" ]]; then
      not_measured "${slug} has no payment method"
    fi
  done <"$gate"
  list_queue_members "${ENRICH_TMP}/members-after.tsv"
  if awk -F'\t' 'NR == FNR {after[$1] = $2; next} ($1 in after) && after[$1] != $2 {bad = 1} END {exit bad ? 0 : 1}' \
      "${ENRICH_TMP}/members-after.tsv" "${ENRICH_TMP}/others.tsv"; then
    not_measured "a pre-marked provider was attempted by the process"
  fi
}

# One mode: gate providers, pre-mark, the process through the launcher, sampling, the service after.
# $1 = 0 (drain) | 1 (per-batch). Sets PROC_PEAK_MB, SVC_PEAK_MB, PG_PEAK_MB, SVC_AFTER_MB,
# PROC_COLD_MS; writes ${ENRICH_TMP}/samples, batches, jvm-exits.
run_mode() {
  local per_jvm="$1" mode since
  mode="$([[ "$per_jvm" == 1 ]] && echo per-batch || echo drain)"
  create_gate_providers "${ENRICH_TMP}/gate.tsv" "${ENRICH_TMP}/sources"
  premark_other_providers "${ENRICH_TMP}/gate.tsv"
  since="$(date +%s)"
  start_process "$per_jvm"
  echo "  process container started (mode ${mode}); sampling every ${CRAWLER_PEAK_SAMPLE_SECONDS}s until it exits"
  sample_until_process_exits "${ENRICH_TMP}/samples" || not_measured "sampling failed (see the line above)"
  PROC_COLD_MS="$(python3 -I -c '
import datetime, re, sys
started = datetime.datetime.fromisoformat(re.sub(r"(\.\d{6})\d*", r"\1", sys.argv[1]).replace("Z", "+00:00"))
for line in sys.stdin:
    if "enrich launch starting:" in line:
        t = datetime.datetime.fromisoformat(re.sub(r"(\.\d{6})\d*", r"\1", line.split()[0]).replace("Z", "+00:00"))
        print(int((t - started).total_seconds() * 1000)); break' \
    "$(docker inspect -f '{{.State.StartedAt}}' "$ECID")" < <(docker logs -t "$ECID" 2>&1))"
  parse_batches "${ENRICH_TMP}/batches"
  validate_mode "${ENRICH_TMP}/gate.tsv" "$since" "$mode"
  docker logs "$ECID" >"${ENRICH_TMP}/process-${mode}.log" 2>&1 || true
  docker rm -f "$ECID" >/dev/null 2>&1 || true
  ECID=""
  read -r PROC_PEAK_MB SVC_PEAK_MB PG_PEAK_MB < <(awk '{if ($2 > p) p = $2; if ($3 > s) s = $3; if ($4 > g) g = $4} END {printf "%d %d %d\n", p / 1024, s / 1024, g / 1024}' "${ENRICH_TMP}/samples")
  run_load
  run_frontend_load
  sleep "$ENRICH_SETTLE_SECONDS"
  SVC_AFTER_MB="$(( $(read_rss_kb) / 1024 ))"
}

# Deletes the providers this invocation created (best effort, admin token): the script's own
# cleanup, not something the process can do.
delete_gate_providers() {
  local id
  [[ -n "${ENRICH_TMP}" && -f "${ENRICH_TMP}/created.tsv" && -n "${TOKEN:-}" ]] || return 0
  while IFS=$'\t' read -r id _; do
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/providers/${id}" || true
  done <"${ENRICH_TMP}/created.tsv"
  rm -f "${ENRICH_TMP}/created.tsv"
}

profile_enrich() {
  local baseline_rss_mb baseline_cold_ms service_cold_ms
  local a_growth a_heap a_heap_note a_sum a_ok=1
  local b_sum b_ok=1 b_max_peak

  check_enrich_prerequisites
  ENRICH_TMP="$(mktemp -d)"
  echo "Expected duration: ~${ENRICH_GATE_PROVIDERS} providers x 30-60 s, i.e. 25-50 min per mode (mode B only if A fails)."
  echo

  echo "=== Baseline: the service alone, no VDS_CRAWLER_*/VDS_ENRICH_*, API + frontend load ==="
  SERVICE_DOCKER_ARGS=()
  start_service
  baseline_cold_ms="$COLD_START_MS"
  run_load
  run_frontend_load
  baseline_rss_mb="$(( $(read_rss_kb) / 1024 ))"
  stop_service
  echo

  echo "=== Service + enrichment process (two JVMs, same image and flags) ==="
  start_replay_servers
  SERVICE_DOCKER_ARGS=(--network-alias "$ENRICH_SERVICE_ALIAS")
  start_service
  service_cold_ms="$COLD_START_MS"
  run_load
  run_frontend_load
  create_service_account
  echo

  echo "=== Mode A: drain (one JVM, ${ENRICH_GATE_PROVIDERS} providers, batches of ${ENRICH_BATCH_SIZE}) ==="
  run_mode 0
  cp "${ENRICH_TMP}/batches" "${ENRICH_TMP}/batches-a"
  local a_proc="$PROC_PEAK_MB" a_svc="$SVC_PEAK_MB" a_pg="$PG_PEAK_MB" a_after="$SVC_AFTER_MB" a_cold="$PROC_COLD_MS"
  # Samples count completed batches, so add one to label the batch in flight.
  awk '{b = $5 + 1; if ($2 > p[b]) p[b] = $2} END {for (b in p) print b, int(p[b] / 1024)}' "${ENRICH_TMP}/samples" | sort -n >"${ENRICH_TMP}/peaks-a"
  local last_batch
  last_batch="$(wc -l <"${ENRICH_TMP}/batches-a" | tr -d ' ')"
  a_growth=$(( $(awk -v b="$last_batch" '$1 == b {print $3}' "${ENRICH_TMP}/batches-a") - $(awk '$1 == 2 {print $3}' "${ENRICH_TMP}/batches-a") ))
  read -r a_heap a_heap_note < <(python3 -I -c '
import sys
rows = [l.split() for l in open(sys.argv[1])]
f = [float(r[4]) for r in rows[1:] if r[4] != "-"]
if len(f) < 2:
    print("0.0 fewer-than-2-batches-with-a-Full-GC")
else:
    trend = "decreasing" if all(b <= a for a, b in zip(f, f[1:])) else "not-decreasing"
    print(f"{f[-1] - f[0]:.1f} {trend}")' "${ENRICH_TMP}/batches-a")
  a_sum=$(( a_svc + a_proc + a_pg ))

  echo
  echo "=== Results, mode A (RSS inside ${JAVA_IMAGE}, flags: ${JVM_FLAGS[*]}; process also -Xlog:gc:stdout) ==="
  printf '  %-5s %9s %16s %14s %16s %9s\n' batch providers "process peak MB" "rssMb at end" "post-Full-GC MB" seconds
  while read -r b providers rss duration fullgc; do
    printf '  %-5s %9s %16s %14s %16s %9d\n' "$b" "$providers" "$(awk -v b="$b" '$1 == b {print $2}' "${ENRICH_TMP}/peaks-a")" "$rss" \
      "$([[ "$fullgc" == "-" ]] && echo "no Full GC" || echo "$fullgc")" "$(( duration / 1000 ))"
  done <"${ENRICH_TMP}/batches-a"
  printf 'service              : baseline %d MB, peak during the process %d MB (reference %s, not deciding), after %d MB\n' \
    "$baseline_rss_mb" "$a_svc" "$ENRICH_SERVICE_PEAK_LIMIT_MB" "$a_after"
  printf 'process              : peak %d MB\n' "$a_proc"
  printf 'PostgreSQL (Pss)     : peak %d MB\n' "$a_pg"
  printf 'sum                  : %d MB = service %d + process %d + PostgreSQL %d, limit <= %s MB\n' \
    "$a_sum" "$a_svc" "$a_proc" "$a_pg" "$ENRICH_SUM_LIMIT_MB"
  printf 'worst case (info)    : %d MB = %s (service limit, tariff crawl at the same time) + process %d + PostgreSQL %d\n' \
    "$(( ENRICH_SERVICE_PEAK_LIMIT_MB + a_proc + a_pg ))" "$ENRICH_SERVICE_PEAK_LIMIT_MB" "$a_proc" "$a_pg"
  printf 'growth               : rssMb batch %d vs batch 2 %+d MB, limit +%s MB\n' "$last_batch" "$a_growth" "$ENRICH_RSS_GROWTH_LIMIT_MB"
  printf 'heap                 : post-Full-GC batch 2 -> last %+.1f MB (%s), limit +%s MB unless decreasing\n' \
    "$a_heap" "$a_heap_note" "$ENRICH_HEAP_GROWTH_LIMIT_MB"
  printf 'cold start           : service %d ms (baseline %d ms), process JVM %d ms\n' "$service_cold_ms" "$baseline_cold_ms" "$a_cold"
  jar_line

  if (( a_growth > ENRICH_RSS_GROWTH_LIMIT_MB )); then echo "  mode A: FAIL growth"; a_ok=0; fi
  if [[ "$a_heap_note" == "not-decreasing" ]] && python3 -I -c "import sys; sys.exit(0 if ${a_heap} > ${ENRICH_HEAP_GROWTH_LIMIT_MB} else 1)"; then
    echo "  mode A: FAIL heap"; a_ok=0
  fi
  if (( a_sum > ENRICH_SUM_LIMIT_MB )); then echo "  mode A: FAIL sum"; a_ok=0; fi
  if (( a_svc > ENRICH_SERVICE_PEAK_LIMIT_MB )); then echo "  mode A: note: service peak above the ${ENRICH_SERVICE_PEAK_LIMIT_MB} MB reference"; fi
  delete_gate_providers

  if (( a_ok == 1 )); then
    echo "MODE: drain"
    EXIT_CODE=0
    stop_service
    stop_replay_servers
    return
  fi

  echo
  echo "=== Mode B: per-batch (one JVM per batch of ${ENRICH_BATCH_SIZE}, relaunched by the launcher) ==="
  run_mode 1
  local b_svc="$SVC_PEAK_MB" b_pg="$PG_PEAK_MB" b_after="$SVC_AFTER_MB"
  # Samples count completed JVMs, so add one to identify the JVM in flight.
  awk '{j = $6 + 1; if ($2 > p[j]) p[j] = $2; if (!(j in s)) s[j] = $1; e[j] = $1}
       END {for (j in p) print j, int(p[j] / 1024), int((e[j] - s[j]) / 1000)}' "${ENRICH_TMP}/samples" | sort -n >"${ENRICH_TMP}/peaks-b"
  b_max_peak="$(awk '{if ($2 > m) m = $2} END {print m + 0}' "${ENRICH_TMP}/peaks-b")"
  b_sum=$(( b_svc + b_max_peak + b_pg ))

  echo
  echo "=== Results, mode B ==="
  printf '  %-6s %8s %14s %9s\n' launch "peak MB" "rssMb at exit" seconds
  while read -r j peak seconds; do
    printf '  %-6s %8s %14s %9s\n' "$j" "$peak" "$(awk -v j="$j" '$1 == j {print $3}' "${ENRICH_TMP}/batches")" "$seconds"
  done <"${ENRICH_TMP}/peaks-b"
  printf 'service              : peak %d MB (reference %s, not deciding), after %d MB\n' "$b_svc" "$ENRICH_SERVICE_PEAK_LIMIT_MB" "$b_after"
  printf 'sum                  : %d MB = service %d + largest per-JVM peak %d + PostgreSQL %d, limit <= %s MB\n' \
    "$b_sum" "$b_svc" "$b_max_peak" "$b_pg" "$ENRICH_SUM_LIMIT_MB"
  printf 'worst case (info)    : %d MB\n' "$(( ENRICH_SERVICE_PEAK_LIMIT_MB + b_max_peak + b_pg ))"
  local jvms first_peak last_peak
  jvms="$(wc -l <"${ENRICH_TMP}/peaks-b" | tr -d ' ')"
  first_peak="$(awk '$1 == 2 {print $2}' "${ENRICH_TMP}/peaks-b")"
  last_peak="$(awk -v j="$jvms" '$1 == j {print $2}' "${ENRICH_TMP}/peaks-b")"
  if (( last_peak - first_peak > ENRICH_RSS_GROWTH_LIMIT_MB )); then
    echo "NOTE: per-JVM peaks rose $(( last_peak - first_peak )) MB from launch 2 to ${jvms}: that growth is outside the JVM."
  fi
  if (( b_sum > ENRICH_SUM_LIMIT_MB )); then echo "  mode B: FAIL sum"; b_ok=0; fi
  if (( b_svc > ENRICH_SERVICE_PEAK_LIMIT_MB )); then echo "  mode B: note: service peak above the ${ENRICH_SERVICE_PEAK_LIMIT_MB} MB reference"; fi
  delete_gate_providers
  stop_service
  stop_replay_servers

  if (( b_ok == 1 )); then
    echo "MODE: per-batch"
    EXIT_CODE=0
  else
    echo "STOP: neither mode fits. Do NOT change JVM flags, batch size, limits, host delay, page budgets, load," >&2
    echo "      sites or pages here -- report it; per ADR vds-002-01 the owner decides." >&2
    echo "STOP"
    EXIT_CODE=1
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
