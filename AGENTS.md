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

## Selector generation for users

When the user wants to generate or inspect selectors independently, direct them to `scripts/source-workbench/README.md`. It documents the workflow for candidate selection, page fetching, selector probes, JSON generation, and validation. Keep its scratch files in a temporary work directory; only copy a reviewed and validated source into `catalog-seed/sources/`. Do not start that workflow or fetch provider sites unless the user explicitly asks.

## Build and checks

- `./gradlew build` builds and tests the backend and bundles the frontend; it requires the Node/npm version in `frontend/.nvmrc`.
- `./gradlew build -PskipFrontend` builds the backend without Node/npm.
- Frontend checks: from `frontend/`, run `npm test` or `npm run build` as appropriate.
- Database integration tests use Testcontainers and require Docker.
- ktlint is not configured. Do not add formatting commands that rewrite files unless requested.
