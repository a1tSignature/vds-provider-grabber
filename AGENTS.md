# AGENTS.md

## Project

VDS/VPS provider catalog

## Architecture and technical decisions

- Backend: Kotlin on JVM 21, Ktor 3 with CIO, Exposed 1.x, PostgreSQL, HikariCP and Flyway. Configuration comes from environment variables and is validated before the service starts.
- Keep HTTP routes, domain behavior, persistence, and external integrations in their existing package boundaries under `src/main/kotlin/org/a1tsign/vds/`.
- The React/TypeScript SPA is built with Vite and served by the same Ktor process as the API. The Vite dev server proxies API requests to the backend; production does not need a separate frontend server or CORS setup.
- Database schema changes are versioned Flyway migrations. Preserve API wire contracts and existing migration history.
- The crawler writes through the catalog HTTP API. Fetch pages through the shared polite, robots-aware fetcher, validate a complete snapshot before writing, and keep discovery separate from tariff crawling.
- Provider-specific crawler sources are declarative JSON interpreted by the shared `DeclarativeAdapter`; do not add a compiled adapter for a source that fits this format. Sources are checked by the same adapter and snapshot validator used by the crawler.
- Discovery and crawling are disabled by default. Do not enable outbound crawling or search integrations as an incidental part of unrelated work.

## Candidate batches

- Selector batches use [the source workbench](scripts/source-workbench/README.md). It has copy-paste commands for a live PostgreSQL snapshot, candidate selection, robots-aware page fetching, selector probes, JSON generation and validation. Keep scratch files under `mktemp -d`. Copy only reviewed and validated JSON into `catalog-seed/sources/` and update `catalog-seed/README.md`.
- For first-time general enrichment in the local Docker stack, configure `VDS_ENRICH_USERNAME` and `VDS_ENRICH_PASSWORD` for an active `EDITOR` in `.env`. The app receives that username as `VDS_CRAWLER_USERNAME`; a role alone cannot read the internal catalog. Run `docker compose up -d --build --wait app` to apply the configuration, then `docker compose --profile enrich run -d --no-deps --name vds-enrich-local enrich --new-candidates --workers 4`. Check progress with `docker logs -f vds-enrich-local`. The job runs independently of the agent and writes general data through the catalog API.
- For tariff writes in local Docker, follow [README §7.1](README.md#71-что-нужно-от-краулера). Set `VDS_CRAWLER_ENABLED=true` in `.env` with an active `EDITOR` and set `VDS_CRAWLER_ADAPTERS` to the batch IDs if a particular batch was requested. Compose mounts `catalog-seed/sources/` read-only. Apply with `docker compose up -d --build --wait app`, then check `docker compose logs -f app` for `crawl run` and `tariffsCreated`.
- Run outbound workflows only when the user explicitly asks. The tariff scheduler runs inside `app`, and general enrichment runs as a separate one-shot process.

## Build and checks

- `./gradlew build` builds and tests the backend and bundles the frontend; it requires the Node/npm version in `frontend/.nvmrc`.
- `./gradlew build -PskipFrontend` builds the backend without Node/npm.
- Frontend checks: from `frontend/`, run `npm test` or `npm run build` as appropriate.
- Database integration tests use Testcontainers and require Docker.
- ktlint is not configured. Do not add formatting commands that rewrite files unless requested.
