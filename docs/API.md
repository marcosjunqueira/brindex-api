# brindex-api reference

A read-only HTTP API over the SQLite database that
[`brindex-ingest`](https://github.com/marcosjunqueira/brindex-ingest) writes to: historical Tesouro
Direto bond prices, PTAX (USD reference rate) and CDI. This API never writes to the database and
never invents or reinterprets a series `code` — it only looks entries up by the exact string
`brindex-ingest` assigned.

See [`.specs/New/SPEC_READ_API.md`](../.specs/New/SPEC_READ_API.md) for the full design rationale.
For wiring a specific consumer (Portfolio Performance) to these endpoints, see
[`PORTFOLIO_PERFORMANCE.md`](PORTFOLIO_PERFORMANCE.md).

## Running the API

```bash
cp .env.example .env   # set BRINDEX_DB_PATH to a database brindex-ingest has already populated
./run.sh                # or: ./gradlew run
```

The server listens on `:8080` by default (`PORT` env var to override) and fails fast at startup if
`BRINDEX_DB_PATH` doesn't point at a database with the `series`/`points` tables already created.

## Authentication

None. This API is for personal use on a private network — do not expose it to the public internet
without adding an auth layer of your own.

## Series `code` identity

Every series has a stable `code` string, canonically `<DOMAIN>:<IDENTIFIER...>`, `:`-joined, e.g.:

| `code`                    | `domain`          |
| ------------------------- | ----------------- |
| `TD:LFT:2026-03-01:BUY`   | `treasury-direct` |
| `PTAX:USD:SELL`           | `ptax`             |
| `CDI:SGS:4391`            | `cdi`              |

Wherever an endpoint below takes `{...code}`, the code's `:`-separated parts are given as separate
URL path segments instead of one URL-encoded token — this keeps the URL readable instead of forcing
clients to percent-encode every `:` as `%3A`:

| `code`                  | Path segments             |
| ----------------------- | -------------------------- |
| `TD:LFT:2026-03-01:BUY` | `TD/LFT/2026-03-01/BUY`    |
| `PTAX:USD:SELL`         | `PTAX/USD/SELL`             |
| `CDI:SGS:4391`          | `CDI/SGS/4391`              |

The number of segments varies by domain, so there's no fixed-arity route — the API rejoins whatever
segments it's given with `:` and looks that string up as-is; it never validates the identifier shape
per domain.

## Endpoints

### `GET /health`

Liveness check. Never touches the database.

**Response:** `200 OK`, body `ok` (plain text).

---

### `GET /series`

List known series.

**Query parameters**

| Name     | Required | Description                                  |
| -------- | -------- | --------------------------------------------- |
| `domain` | no       | Filter to one domain (`treasury-direct`, `ptax`, `cdi`). Omit to list every series. |

**Response:** `200 OK`, a JSON array, one object per series, ordered by `code`:

```json
[
  {
    "code": "PTAX:USD:SELL",
    "domain": "ptax",
    "name": "PTAX — USD, sell rate",
    "metadata": { "...": "domain-specific, opaque" }
  }
]
```

`metadata` is emitted as a real JSON object/array (not a JSON-encoded string) even though it is
stored as SQLite `TEXT` — see [Decimal and JSON discipline](#decimal-and-json-discipline) below.

**Example**

```bash
curl -sS "http://localhost:8080/series?domain=treasury-direct"
```

---

### `GET /series/{...code}/points`

Historical points for one series, optionally restricted to a date range.

**Query parameters**

| Name    | Required | Format       | Description                                          |
| ------- | -------- | ------------ | ----------------------------------------------------- |
| `since` | no       | `YYYY-MM-DD` | Omit for "from the earliest point".                   |
| `until` | no       | `YYYY-MM-DD` | Omit for "up to the latest point". Both bounds are inclusive. |

**Response:** `200 OK`, a JSON array ordered by `date` ascending:

```json
[
  {
    "date": "2026-03-01",
    "value": "18094.98",
    "extra_values": null,
    "source_updated_at": "2026-03-02T10:15:00Z"
  }
]
```

- `value` is emitted as a raw JSON number, but only ever from the exact decimal digits stored —
  never round-tripped through `Double`/`Float`. It is `null`, not `0` or omitted, when the source
  had no price for that date.
- `extra_values` is domain-specific opaque JSON (e.g. PU buy/sell alongside a base PU), or `null`.

**Errors**

| Status | Body                                | When                                      |
| ------ | ------------------------------------ | ------------------------------------------ |
| `400`  | `{"error": "since must be YYYY-MM-DD"}` (or `until`) | a date query param isn't in `YYYY-MM-DD` form |
| `404`  | `{"error": "series not found"}`      | no series exists with that `code`          |

An unknown `code` with valid/absent date params returns `404`, not `200` with an empty array — an
empty array only ever means "this series exists but has no points in the requested range."

**Example**

```bash
curl -sS "http://localhost:8080/series/TD/LFT/2026-03-01/BUY/points?since=2026-01-01&until=2026-03-01"
```

---

### `GET /series/{...code}/points/latest`

The single most recent point for a series — the common "what's today's price" query.

**Response:** `200 OK`, one point object (same shape as an element of `/points`'s array):

```json
{
  "date": "2026-09-08",
  "value": "18120.55",
  "extra_values": null,
  "source_updated_at": "2026-09-09T06:00:00Z"
}
```

**Errors**

| Status | Body                                      | When                                     |
| ------ | ------------------------------------------ | ------------------------------------------ |
| `404`  | `{"error": "series not found"}`            | no series exists with that `code`          |
| `404`  | `{"error": "no points for series"}`        | the series exists but has zero points     |

**Example**

```bash
curl -sS "http://localhost:8080/series/PTAX/USD/SELL/points/latest"
```

---

## Error format

Every non-2xx response (including an unhandled server-side failure) is a JSON object with a single
`error` string field:

```json
{ "error": "series not found" }
```

An unhandled exception (a malformed stored JSON blob, a missing table, etc.) is caught by a global
handler and returned as `500` in this same shape, rather than Ktor's default bare error response.

## Decimal and JSON discipline

`points.value` and any numeric field nested inside `extra_values`/`metadata` are stored in SQLite as
`TEXT`, never as float, specifically to keep round-off out of the historical record. The API
preserves this on the way out:

- `value` is spliced into the response as a raw JSON number token from the stored string itself —
  never parsed into a `Double` and re-emitted. High-precision decimals and trailing zeros
  (`18094.980`) come out exactly as stored, not normalized (`18094.98`).
- `metadata`/`extra_values` blobs are validated as well-formed JSON and then spliced in verbatim,
  for the same reason: generic JSON parsing would materialize any bare numeric literal inside them
  as a `Double` and silently reformat it on re-serialization.
- A missing price is always SQL `NULL` → JSON `null`. It is never coerced to `0` and the field is
  never dropped — a missing price and a zero price must stay distinguishable to a caller.

## Non-goals (v1)

- **No authentication.** Personal use, private network only.
- **No write endpoints.** All ingestion happens out-of-process in `brindex-ingest`.
- **No pagination.** `/points` returns the full matching range in one response; this is an open
  question for future large date ranges, not yet designed.
