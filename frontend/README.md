# frontend

React + TypeScript SPA served by the same Ktor process as the API (see the root `README.md`).

## Requirements

Node version from `.nvmrc`, currently `22.22.0`. `vitest@5.1.0` and `react-router@8.5.1` require at
least that patch; a plain `>=22.0.0` is not enough.

## Development

```bash
npm ci
npm run dev
```

`vite`'s dev server proxies `/api`, `/health` and `/version` to `http://localhost:8080`, so run the
backend there first (`./gradlew run` or the packaged jar) and open the printed `localhost` URL.
Same-origin in dev, same as in production — no CORS configuration is ever needed.

## Build and test

```bash
npm run build   # tsc --noEmit, then the production bundle into dist/
npm test        # vitest run, including the source guard tests
```

## How Gradle picks this up

`./gradlew build` runs `npm ci` + `npm run build` here (Gradle task `buildFrontend`), then copies
`dist/` into the backend's resources before packaging the fat jar. Nothing is written into
`src/main/resources`. A second `./gradlew build` with nothing changed reports `buildFrontend
UP-TO-DATE`.

Run `./gradlew build -PskipFrontend` to build the backend alone, with no Node/npm required; the
resulting jar serves the API with no `static/` content.
