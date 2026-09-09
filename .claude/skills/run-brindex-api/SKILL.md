---
name: run-brindex-api
description: Build, run, and drive brindex-api (the Kotlin/Ktor read-only HTTP API over the BRIndex SQLite database). Use when asked to start brindex-api, build it, run its tests, or hit its endpoints (/health, /series, /series/{...code segments}/points, /series/{...code segments}/points/latest).
---

brindex-api is a Kotlin + Ktor (Netty) server with no UI — drive it with
`curl`. The fastest way to see it working end-to-end is the smoke script at
`.claude/skills/run-brindex-api/smoke.sh`, which seeds a throwaway fixture
SQLite DB, launches the server against it, and curls every endpoint.

All paths below are relative to the repo root (the directory containing
`build.gradle.kts`).

## Prerequisites

JDK 25 must be on `PATH` (the Gradle toolchain pins to 25). This container
already had it — `java -version` reported
`openjdk version "25.0.4"` (`/usr/lib/jvm/java-25-openjdk-amd64`). If it's
missing:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-25-jdk
```

`curl`, `lsof`, and `python3` are used by the smoke script (all present by
default on Ubuntu/Pop!_OS).

## Setup

No separate install step — Gradle (via the wrapper) pulls all dependencies
on first build/run.

## Build

```bash
./gradlew compileKotlin
```

## Run (agent path)

Run the smoke script — it builds, seeds a fixture DB, launches the server,
exercises every endpoint, and tears everything down:

```bash
./.claude/skills/run-brindex-api/smoke.sh
```

Expected tail of output:

```
== GET /health ==
ok
== GET /series ==
[{"code":"PTAX:USD:SELL", ...}, {"code":"TD:LFT:2026-03-01:BUY", ...}]
== GET /series/{...code segments}/points/latest ==
{"date":"2026-01-06","value":1234.5678901234,"extra_values":null,"source_updated_at":"2026-09-09T00:00:00Z"}
== GET /series/{unknown}/points -> expect 404 ==
status: 404
== smoke test passed ==
```

Server log lands at `/tmp/brindex-api.log` (override with `LOG_PATH=...`).
Port defaults to `8080` (override with `PORT=...`).

To drive the server manually instead of via the script (e.g. to explore a
new endpoint interactively), launch it the same way the script does, against
your own SQLite file:

```bash
BRINDEX_DB_PATH=/path/to/some.sqlite ./gradlew -q run > /tmp/brindex-api.log 2>&1 &
SERVER_PID=$!
for i in $(seq 1 60); do curl -sf http://localhost:8080/health > /dev/null && break; sleep 1; done
curl -sS "http://localhost:8080/series?domain=ptax"
```

Stop it by killing the port's listener (`$SERVER_PID` is the `gradlew`
wrapper process; it does not forward `SIGTERM` to the JVM it spawns):

```bash
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill
```

The canonical `code` is `:`-joined (e.g. `TD:LFT:2026-03-01:BUY`), but the API
never expects that string as a single URL-encoded path segment — each
`:`-separated part is its own path segment instead:
`/series/TD/LFT/2026-03-01/BUY/points`. The server reconstructs the `:`-joined
`code` internally before querying the database.

### Environment

| Variable | Required | Default | Notes |
|---|---|---|---|
| `BRINDEX_DB_PATH` | No | `brindex.sqlite` (relative to CWD) | Path to the SQLite file `brindex-ingest` writes. Must already have `series`/`points` tables — this API never creates them, and fails fast at startup (not on first request) if they're missing. |
| `PORT` | No | `8080` | Read via `System.getenv("PORT")` in `Application.kt`'s `main()`. A set-but-invalid value (non-numeric, or outside 1-65535) fails startup loudly rather than silently falling back to `8080`. |

## Run (human path)

```bash
./gradlew run   # blocks; Ctrl-C to stop. Reads BRINDEX_DB_PATH the same way.
```

## Test

```bash
./gradlew test
```

20 tests in `SeriesRoutesTest` (fixture-DB-backed contract tests for all
three data endpoints, including malformed-input and malformed-stored-data
error paths) plus 1 in `ApplicationTest` (`/health`) — all pass.

---

## Gotchas

- **`/health` returns plain text `ok`, not JSON.** Every other endpoint
  returns JSON via kotlinx.serialization (`ContentNegotiation` + `json()`),
  so don't `curl | jq` the health check.
- **Decimal values come back as bare JSON numbers, not strings** — e.g.
  `"value":1234.5678901234`, not `"value":"1234.5678901234"`. This is
  deliberate (see `RawJsonNumberSerializer` in `SeriesRoutes.kt`): the
  server writes the SQLite `TEXT` column's exact digits as an unquoted JSON
  literal, never passing through `Double`/`Float`. Don't "fix" a test that
  expects an unquoted number thinking it's a bug.
- **`points.value` can legitimately be `NULL`** (a missing/malformed source
  value, per spec §6/§7) — `brindex-ingest` had a bug where its schema
  declared `value TEXT NOT NULL`, contradicting its own spec; fixed
  upstream, so any DB regenerated from a current `brindex-ingest` allows
  `NULL` there. If you hit `SQLITE_CONSTRAINT_NOTNULL` inserting a `NULL`
  `value` into a hand-built fixture, your `CREATE TABLE points` is stale —
  drop the `NOT NULL` on that column.
- **`./gradlew run` blocks the shell.** Always background it (`&`) and poll
  `/health` before curling anything else — the JVM takes a few seconds to
  start even after Gradle itself returns control.
- **Killing the server**: `$!` right after `./gradlew run &` is the
  `gradlew` wrapper's PID, not the JVM's — `kill`ing it alone can leave the
  actual server process (and port `8080`) running. Kill by port instead:
  `lsof -ti:8080 -sTCP:LISTEN | xargs -r kill`. Careful with this if
  something else might be using that port — it kills whatever's listening,
  not specifically your instance. `smoke.sh` narrows this by capturing the
  listening PID right after its own health check confirms it's up, rather
  than blindly querying the port again at cleanup time.
- **A native-access warning is normal and harmless**: sqlite-jdbc triggers
  `WARNING: A restricted method in java.lang.System has been called ...
  org.sqlite.SQLiteJDBCLoader`. It doesn't affect behavior; ignore it.
