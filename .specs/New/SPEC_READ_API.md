# Specification — Read-only HTTP API over the BRIndex historical series database

**Status:** New — `/health` and all three data endpoints (`/series`, `/series/.../points`,
`/series/.../points/latest`) are implemented; response shape and error format were settled by the
first implementation PR, pagination remains open (see §4)
**Date:** 2026-09-09
**Repo:** `brindex-api`
**Related:** `brindex-ingest` (writes the SQLite database this API reads from) — the two repos are
independently deployable and share no code, only the database file and the identity scheme below.

---

## 1. Purpose

Serve read-only HTTP access to the historical time series `brindex-ingest` collects daily from
official Brazilian public data sources: Tesouro Direto bond prices (PU), PTAX (USD reference rate)
and CDI. No write endpoints exist or are planned in this API — all writes happen in `brindex-ingest`
directly against the database file.

## 2. Data model (owned by `brindex-ingest`, consumed here)

Two tables, SQLite, shared by file path (both processes point at the same `.sqlite` file — no
network boundary between ingestion and API at this stage):

```sql
CREATE TABLE series (
  code          TEXT PRIMARY KEY,   -- e.g. 'TD:LFT:2026-03-01', 'PTAX:USD:SELL', 'CDI:SGS:4391'
  domain        TEXT NOT NULL,      -- 'treasury-direct' | 'ptax' | 'cdi'
  name          TEXT NOT NULL,
  metadata      TEXT NOT NULL,      -- opaque JSON, domain-specific
  created_at    TEXT NOT NULL
);

CREATE TABLE points (
  series_code   TEXT NOT NULL REFERENCES series(code),
  date          TEXT NOT NULL,      -- YYYY-MM-DD
  value         TEXT NOT NULL,      -- decimal as STRING, never float — see §5
  extra_values  TEXT,               -- optional JSON, e.g. PU buy/sell alongside PU base
  source_updated_at TEXT NOT NULL,
  PRIMARY KEY (series_code, date)
);
```

The identity scheme (`<DOMAIN>:<IDENTIFIER>`) is decided in `brindex-ingest`'s own spec and mirrored
here — this API never invents or reinterprets a `code`, it only looks entries up by the exact
string. PTAX codes are `PTAX:USD:BUY`/`PTAX:USD:SELL`; Tesouro Direto codes (e.g.
`TD:LFT:2026-03-01`) and CDI codes are language-neutral.

## 3. Endpoints (planned, not yet implemented)

- `GET /health` — implemented. Returns `200 ok`. Used for liveness checks.
- `GET /series?domain=<domain>` — list known series, optionally filtered by domain. Returns
  `code`, `domain`, `name`, `metadata` (as parsed JSON, not the raw string) per row.
- `GET /series/{...code segments}/points?since=<YYYY-MM-DD>&until=<YYYY-MM-DD>` — historical points
  for one series in a date range. Both `since` and `until` optional; absent `since` means "from the
  earliest point", absent `until` means "up to the latest".
- `GET /series/{...code segments}/points/latest` — the single most recent point for a series. The
  most common query shape ("what's today's PU for this bond").

All three data endpoints are implemented. Response shape (JSON field casing, error format for an
unknown `code`) was settled by the first implementation PR; pagination for `/points` over a very
long range remains an open design question, deferred per §4.

### 3.1 `code` as path segments, not a single URL-encoded token

`series.code` is canonically `<DOMAIN>:<IDENTIFIER...>`, `:`-joined (§2). Exposing that string as a
single path segment would force every client to URL-encode each `:` as `%3A` — e.g.
`/series/TD%3ALFT%3A2026-03-01%3ABUY/points` — which is illegible. Instead the API decomposes the
code into one path segment per `:`-separated part, with the domain as the first segment:

| `code`                    | Path                              |
| ------------------------- | ---------------------------------- |
| `TD:LFT:2026-03-01:BUY`   | `/series/TD/LFT/2026-03-01/BUY/points` |
| `PTAX:USD:SELL`           | `/series/PTAX/USD/SELL/points`         |
| `CDI:SGS:4391`            | `/series/CDI/SGS/4391/points`          |

The number of identifier segments varies by domain, so the route captures the whole tail after
`/series/` and the handler recovers the exact stored `code` by rejoining the segments with `:`
before ever querying the database — this API still never invents or reinterprets a `code`, it just
changes how one is spelled in a URL. There is no deliberate backward-compatible redirect from the
old single-segment/URL-encoded form: this endpoint was never released to a consumer other than this
repo's own tests, so the breaking change ships directly rather than carrying compatibility weight
for a format nothing depends on. In practice the old form still happens to resolve — Ktor decodes
each path segment before routing sees it, so `TD%3ALFT%3A2026-03-01%3ABUY` arrives as one segment
whose value already contains `:`, and rejoining a single segment with `:` is a no-op that recovers
the same code — but this is incidental, not a documented or guaranteed compatibility path.

## 4. Non-goals for v1

- **No authentication.** Personal use only, expected to run on a private network or be reachable
  only by the user's own `cornerstone-app`. An API-key/rate-limiting layer is an explicit future
  phase, not designed here.
- **No write endpoints.** All ingestion happens in `brindex-ingest`, out of process.
- **No pagination design yet** for `/points` over multi-year ranges — deferred until real data
  volume makes it necessary to decide.

## 5. Money/decimal discipline

`value` and any numeric field inside `extra_values`/`metadata` must be serialized as JSON numbers
only from a decimal representation that was never a `Double`/`Float` in application code — SQLite
stores them as `TEXT` specifically to avoid float round-off silently entering the historical record.
A missing/unparseable value from a source must be persisted as SQL `NULL`, never as `NaN` or a
sentinel string. This API's serialization layer must preserve that: never coerce a `NULL` price into
`0` or omit the field silently — a missing price is meaningfully different from a zero price and
callers need to be able to tell them apart.

## 6. Testing

- `ApplicationTest.kt` (`health endpoint responds ok`) — implemented, passing (`./gradlew test`).
- Each new endpoint needs a `testApplication` contract test against a database seeded with known
  fixture rows (not a live `brindex-ingest` run) — mirrors the "always fixture, never hit the real
  source in CI" discipline `brindex-ingest`'s own spec establishes for its parsers.
- A `code` that doesn't exist in `series` must return a clear 404, not a 200 with an empty body or
  a 500 — test this explicitly once implemented.
