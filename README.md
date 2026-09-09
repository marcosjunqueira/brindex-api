# brindex-api

Read-only HTTP API over BRIndex's historical series of official Brazilian market data
(Tesouro Direto, PTAX, CDI). Serves the database populated by
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

## License

MIT — see [LICENSE](LICENSE).
