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

- `ghcr.io/a1tsign/vds-provider-grabber:1.2.3`
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
to the public internet; neither is published by this Compose project. Reserve at least 4 GiB RAM
for the host; the application, database, Caddy, and optional enrichment process have container
limits totalling about 1.9 GiB, before Docker and operating-system overhead. Database storage grows
with the catalogue, so monitor free disk space.

The server only needs these deployment files; it does not need the source tree or a local build:

```text
deploy/stand/docker-compose.yml
deploy/stand/Caddyfile
deploy/stand/.env.example
deploy/stand/init-env.sh
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
- `VDS_IMAGE` to the release you published, for example
  `ghcr.io/a1tsign/vds-provider-grabber:1.2.3`;
- database, JWT, bootstrap-admin, and enrichment secrets as needed.

`init-env.sh` generates secrets and refuses to overwrite an existing `.env`. Keep the file and the
PostgreSQL volume together: the database password is initialized into that volume on first start.

## 3. Start and verify

For a private GHCR package, log in interactively (Docker prompts for a token with `read:packages`)
or provide one through a secret manager using `--password-stdin`:

```sh
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USER" --password-stdin
```

Then:

```sh
docker compose config -q
docker compose pull
docker compose up -d --wait
docker compose ps
```

Open `https://<VDS_SITE_ADDRESS>` and sign in with the bootstrap administrator from `.env`. The
bootstrap account is created only when the database has no users. The schema is migrated by the
application before readiness succeeds.

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

The `enrich` service is an opt-in one-shot process. Its systemd timer setup and credentials are
documented in the root README's deployment section. It uses the same `VDS_IMAGE` as the app.
