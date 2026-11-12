#!/usr/bin/env sh
# Creates .env from .env.example, replacing every __GENERATE__ with its own random secret.
# Refuses to overwrite an existing .env: the database password in it is already baked into the volume.
set -eu
cd "$(dirname "$0")"

if [ -e .env ]; then
  echo ".env already exists; remove it first to regenerate." >&2
  exit 1
fi

umask 077
while IFS= read -r line; do
  case "$line" in
    *=__GENERATE__) printf '%s=%s\n' "${line%%=*}" "$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40)" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < .env.example > .env

echo "created $(pwd)/.env; set VDS_SITE_ADDRESS before the first start."
