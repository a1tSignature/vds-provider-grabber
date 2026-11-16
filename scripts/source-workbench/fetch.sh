#!/usr/bin/env bash
# Usage: fetch.sh <work dir> [parallelism]
# Downloads every page listed in <work dir>/rest.tsv into <work dir>/pages/<slug>.html the way the
# crawler sees it (plain GET, no JavaScript), logs `slug status bytes` to fetch.log and lists the
# usable pages (HTTP 200, > 3 KB) in auto.txt for the workbench's `auto` step.
set -euo pipefail
work="$1"
jobs="${2:-16}"
mkdir -p "$work/pages"
export work
xargs -P "$jobs" -n 2 sh -c '
  curl -sL --compressed -m 25 -A "vds-provider-grabber/0.1" -o "$work/pages/$0.html" \
    -w "$0\t%{http_code}\t%{size_download}\n" "$1" || true
' < "$work/rest.tsv" > "$work/fetch.log"
awk -F'\t' '$2 == 200 && $3 > 3000 { print $1 }' "$work/fetch.log" > "$work/auto.txt"
wc -l < "$work/auto.txt"
