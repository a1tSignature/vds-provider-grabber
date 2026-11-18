#!/usr/bin/env bash
# Launch site enrichment under a nonblocking lock. Forward all arguments to every JVM.
# Batch mode relaunches on exit 10. A crash can be retried up to three times within the launch budget.
# A shared attempted-provider file prevents a restarted JVM from retrying the provider that was in flight.
# Launcher exits: 0 drained, busy or budget exhausted, 1 fatal failure, 2 crash retries exhausted, 143 terminated.
set -uo pipefail

JVM_FLAGS=(
  -Xmx192m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m
  -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC
)
MAIN_CLASS=org.a1tsign.vds.crawler.enrich.EnrichMainKt
MAX_CRASH_RELAUNCHES=3
EXIT_DRAINED=0
EXIT_FATAL=1
EXIT_STOPPED_EARLY=10

LOCK_FILE=${VDS_ENRICH_LOCK_FILE:-/var/lock/vds-enrich/lock}
JAR=${VDS_ENRICH_JAR:-/app/app.jar}
MODE=${VDS_ENRICH_BATCHES_PER_JVM:-0}
BUDGET_MINUTES=${VDS_ENRICH_MAX_RUNTIME_MINUTES:-360}
# Measurement only (e.g. -Xlog:gc:stdout); word-split on purpose.
read -r -a EXTRA_JVM_FLAGS <<< "${VDS_ENRICH_EXTRA_JVM_FLAGS:-}"

log() { printf '%s enrich-launch: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

case "$MODE" in
  0|1) ;;
  *) log "VDS_ENRICH_BATCHES_PER_JVM must be 0 or 1, got '$MODE'"; exit "$EXIT_FATAL" ;;
esac
if ! [[ "$BUDGET_MINUTES" =~ ^[1-9][0-9]*$ ]]; then
  log "VDS_ENRICH_MAX_RUNTIME_MINUTES must be a positive integer"
  exit "$EXIT_FATAL"
fi

mkdir -p "$(dirname "$LOCK_FILE")" 2>/dev/null
if ! exec 9>>"$LOCK_FILE"; then
  log "cannot open the lock file $LOCK_FILE"
  exit "$EXIT_FATAL"
fi
if ! flock -n 9; then
  log "previous launch still running; exiting"
  exit 0
fi

ATTEMPTED_FILE=$(mktemp /tmp/vds-enrich-attempted.XXXXXX) || { log "cannot create the attempted-file"; exit "$EXIT_FATAL"; }
trap 'rm -f "$ATTEMPTED_FILE"' EXIT

child=""
on_signal() {
  log "terminated; stopping the JVM"
  [[ -n "$child" ]] && kill -TERM "$child" 2>/dev/null && wait "$child"
  exit 143
}
trap on_signal TERM INT

budget_seconds=$((BUDGET_MINUTES * 60))
mode_args=()
[[ "$MODE" == 1 ]] && mode_args=(--max-batches 1)
crashes=0
jvms=0
log "launch starting: mode=$([[ "$MODE" == 1 ]] && echo per-batch || echo drain) budgetMinutes=$BUDGET_MINUTES"

while true; do
  # Each JVM gets what is left of the launch budget, rounded up to whole minutes.
  remaining_minutes=$(( (budget_seconds - SECONDS + 59) / 60 ))
  jvms=$((jvms + 1))
  env -u JAVA_TOOL_OPTIONS VDS_ENRICH_MAX_RUNTIME_MINUTES="$remaining_minutes" \
    java "${JVM_FLAGS[@]}" ${EXTRA_JVM_FLAGS[@]+"${EXTRA_JVM_FLAGS[@]}"} -cp "$JAR" "$MAIN_CLASS" \
    --attempted-file "$ATTEMPTED_FILE" ${mode_args[@]+"${mode_args[@]}"} "$@" &
  child=$!
  wait "$child"
  code=$?
  child=""
  log "jvm $jvms exited $code"

  case "$code" in
    "$EXIT_DRAINED")
      log "launch finished: queue drained after $jvms JVM(s)"
      exit 0 ;;
    "$EXIT_FATAL")
      log "launch failed: fatal error in the JVM"
      exit "$EXIT_FATAL" ;;
    "$EXIT_STOPPED_EARLY")
      if (( SECONDS >= budget_seconds )) || [[ "$MODE" != 1 ]]; then
        log "launch finished: stopped early; the next launch continues"
        exit 0
      fi ;;
    *)
      crashes=$((crashes + 1))
      if (( crashes > MAX_CRASH_RELAUNCHES )); then
        log "launch failed: $crashes crashes, relaunches exhausted"
        exit 2
      fi
      if (( SECONDS >= budget_seconds )); then
        log "launch failed: crash after the runtime budget was spent"
        exit 2
      fi
      log "crash $crashes of $MAX_CRASH_RELAUNCHES allowed; relaunching" ;;
  esac
done
