# Server deployment

The stand runs Caddy (automatic HTTPS) in front of the application and a private PostgreSQL
container. Only Caddy publishes ports. The application image is published to GHCR by the
`Build` workflow after checks pass for a pushed `v*` tag; it supports `linux/amd64` and
`linux/arm64`.

## 1. Publish a version

After the intended commit is on `master`, create and push a semantic version tag:

```sh
git tag v1.2.3
git push origin v1.2.3
```

The workflow builds and tests the project, then publishes:

- `ghcr.io/a1tsignature/vds-provider-grabber:1.2.3`
- minor and major aliases (`:1.2`, `:1`)
- `:latest` for stable semantic versions
- a commit tag (`:sha-…`)

Use a fixed version tag in the server `.env`. The registry package must be public for anonymous
pulls; for a private package, authenticate the server to `ghcr.io` with a GitHub token that has
`read:packages` access. Never put that token in the compose file or `.env`.

## 2. Prepare the host

Install Docker Engine with the Compose v2 plugin. Configure DNS for the domain to point to the
server, and allow inbound TCP ports 80 and 443. Caddy uses them to obtain and renew a Let's Encrypt
certificate. UDP 443 may also be opened for HTTP/3. Keep port 8080 and PostgreSQL port 5432 closed
to the public internet; neither is published by this Compose project.

The enrichment footprint check budgets 700 MB for the combined peaks of the application,
enrichment process, and PostgreSQL. The check uses dedicated JVM flags and excludes Caddy.
Validate the deployment's combined peak with its actual JVM settings and crawler schedules.
Allow separate headroom for Docker, the operating system, and any other services on the host.
Database storage grows with the catalogue, so monitor free disk space.

The server only needs these deployment files; it does not need the source tree or a local build:

```text
deploy/stand/docker-compose.yml
deploy/stand/Caddyfile
deploy/stand/.env.example
deploy/stand/init-env.sh
deploy/stand/vds-enrich.service
deploy/stand/vds-enrich.timer
```

Copy them to `/opt/vds-stand` (or another persistent directory), then initialize configuration:

```sh
cd /opt/vds-stand
sudo install -d -o 70 -g 70 -m 0750 backups
chmod 700 init-env.sh
./init-env.sh
chmod 600 .env
```

Edit `.env` and set:

- `VDS_SITE_ADDRESS` to the public DNS name;
- `VDS_CORS_ALLOWED_ORIGINS` to the exact browser origin when TLS terminates upstream,
  including any non-default port, for example `https://vds.example.com:8443`;
- `VDS_IMAGE` to the release you published, for example
  `ghcr.io/a1tsignature/vds-provider-grabber:1.2.3`;
- database, JWT, bootstrap-admin, and enrichment secrets as needed.

`init-env.sh` generates secrets and refuses to overwrite an existing `.env`. Keep the file and the
PostgreSQL volume together: the database password is initialized into that volume on first start.

## 3. Start and verify

For a private GHCR package, log in interactively (Docker prompts for a token with `read:packages`)
or provide one through a secret manager using `--password-stdin`:

```sh
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USER" --password-stdin
```

To start with the supplied PostgreSQL 17 catalog snapshot, copy `catalog-stand.dump` to
`backups/` and restore it into a fresh stand database **before starting the app**.
The archive includes Flyway history. The app creates the bootstrap admin on first start.

```sh
docker compose up -d --wait postgres
docker compose exec -T postgres sh -c \
  'pg_restore --single-transaction --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backups/catalog-stand.dump
```

Keep the dump outside Git. For a new empty catalog, skip the restore.
Extract the optional `catalog-sources.tar.gz` in the stand directory to install the read-only
selector set under `sources/`. Then start the stack.


```sh
docker compose config -q
docker compose pull
docker compose up -d --wait
docker compose ps
```

Open `https://<VDS_SITE_ADDRESS>` and sign in with the bootstrap administrator from `.env`. The
bootstrap account is created only when the database has no users. The schema is migrated by the
application before readiness succeeds.

### Weekly discovery and candidate enrichment

Create an active `EDITOR` account using the username and password configured in
`VDS_ENRICH_USERNAME` and `VDS_ENRICH_PASSWORD`. Use the bootstrap administrator
to call `POST /api/v1/admin/users` with `username`, `password` and `roles: ["EDITOR"]`.

For discovery, set `VDS_DISCOVERY_SEARCH_API_KEY` and `VDS_DISCOVERY_ENABLED=true` in
`.env`, then recreate the app with `docker compose up -d --wait app`. It schedules
discovery approximately weekly and persists the search ledger in the `discovery-state`
volume. Each Tavily basic search uses one credit. The stand caps a pass at 10 requests
and ends the pass on a rate limit. The tariff scheduler stays disabled.

Install the enrichment timer on the Linux stand after configuring the editor account.
Adjust `WorkingDirectory` in the service if the stand uses a different path.

```sh
sudo install -m 0644 vds-enrich.service vds-enrich.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now vds-enrich.timer
sudo systemctl list-timers vds-enrich.timer
```

The timer waits 72 hours after activation and runs every 72 hours while active.
Each run processes up to 20 candidates with no previous enrichment attempt, using
4 workers. Inspect run logs with `journalctl -u vds-enrich`.

### Start with the local image archive

Load `catalog-images.tar.gz` on the stand with `docker load -i catalog-images.tar.gz`
and set `VDS_IMAGE=vds-provider-grabber:release-stand`. Add a `docker-compose.override.yml`
next to the stand configuration so the app uses the imported image:

```yaml
services:
  app:
    pull_policy: never
```

Use the same restore and startup steps above and skip `docker compose pull`.

## 4. Upgrade and recover

Before an upgrade, take a PostgreSQL backup (see the backup commands in the root README). Change
`VDS_IMAGE` to the new fixed release tag, then run:

```sh
docker compose pull
docker compose up -d --wait
```

Check `docker compose ps` and `docker compose logs --tail=100 app`. Migrations run at application
startup. Keep the previous image tag and a recent database backup available; database migrations
are forward-only, so restoring an old image alone may not be compatible with an already-migrated
database.

The `enrich` service is an opt-in one-shot process. It uses the same `VDS_IMAGE` as the app.
