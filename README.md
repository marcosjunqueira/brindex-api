# brindex-api

Read-only HTTP API over BRIndex's historical series of official Brazilian market data
(Tesouro Direto, PTAX, CDI, B3 daily closes). Serves the database populated by
[`brindex-ingest`](https://github.com/marcosjunqueira/brindex-ingest).

## Status

Early scaffold. See [`.specs/`](.specs/) for the design.

## Stack

- Kotlin + [Ktor](https://ktor.io/) (`Netty` engine)
- SQLite (via `sqlite-jdbc`) — same database file `brindex-ingest` writes to
- Gradle (wrapper committed, JDK 25 toolchain)

## Running

```bash
./gradlew run
```

Health check: `GET /health` → `ok`.

## Docker image and releases

Each `vX.Y.Z` tag on `main` publishes `ghcr.io/marcosjunqueira/brindex-api:X.Y.Z` (plus the floating
`X.Y`, `X` and `latest` tags when it is the highest release in that range; no bare `X` while the
major version is 0) and a
GitHub Release. The tag must match `version` in `build.gradle.kts`. Production runs it with [`deploy/docker-compose.yml`](deploy/docker-compose.yml);
see [`docs/DEPLOY.md`](docs/DEPLOY.md) (setup and releasing) and [`docs/GO_LIVE.md`](docs/GO_LIVE.md).

Local image:

```bash
docker build -t brindex-api .
docker run --rm -p 8080:8080 -v "$PWD/data:/data" --user "$(id -u):$(id -g)" brindex-api   # expects data/brindex.sqlite
```

## Guides

- [Configuring Portfolio Performance to use brindex-api](docs/PORTFOLIO_PERFORMANCE.md)

## License

MIT — see [LICENSE](LICENSE).
