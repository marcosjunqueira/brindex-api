# Configuring Portfolio Performance to use brindex-api

[Portfolio Performance](https://www.portfolio-performance.info/) can pull a security's price
history from a generic JSON feed. This documents how to point it at `brindex-api` instead of an
external webhook, using the `GET /series/{...code segments}/points` and
`GET /series/{...code segments}/points/latest` endpoints from
[`SPEC_READ_API.md`](../.specs/New/SPEC_READ_API.md) (or wherever it has landed in the `.specs/`
lifecycle by the time you read this).

## Prerequisites

- `brindex-api` running and reachable from the machine Portfolio Performance runs on — usually
  `localhost:8080` when both run on the same machine. The API has no auth (see CLAUDE.md's
  non-goals), so only expose it on a private network you trust.
- The `code` of the security in `brindex-ingest`'s database. List candidates by domain, e.g. for
  Tesouro Direto:

  ```bash
  curl -sS "http://localhost:8080/series?domain=treasury-direct"
  ```

  Treasury Direct codes are split by side at the `code` level (e.g. `TD:LFT:2031-03-01:BUY` /
  `TD:LFT:2031-03-01:SELL` are two separate series, not one series with a BUY/SELL pair inside
  `value`). For marking a held position to market, the **SELL** side — the price you'd receive
  redeeming today — is normally the right one to use; use **BUY** instead if you specifically want
  to track the price you'd pay to invest.

  The API takes `code` as one path segment per `:`-separated part rather than a single
  `:`-joined, URL-encoded token — `TD:LFT:2031-03-01:SELL` becomes `TD/LFT/2031-03-01/SELL` in
  the feed URL below (see `SPEC_READ_API.md` §3.1).

## Historical Quotes tab

| Field | Value |
|---|---|
| Provider | `JSON` |
| Feed URL | `http://localhost:8080/series/<code segments>/points` (one path segment per `:`-separated part of `code`, e.g. `TD/LFT/2031-03-01/SELL`) |
| Date path | `$[*].date` |
| Date format | `yyyy-MM-dd` |
| Date timezone | irrelevant — `date` carries no time component |
| Closing price path | `$[*].value` |
| Daily low / high path | leave empty — Tesouro Direto has no intraday low/high, it's one price fixed per day |
| Volume path | leave empty — not provided |

`GET /points` already scopes to the one series in the URL path, so there's no need for a filter
expression like `$[?(@.Simbolo == '...')]` the way a feed mixing multiple securities in one
response would need — every element of the returned array belongs to the series named by
`<code segments>`.

## Last Quote tab

Same idea, but `GET /points/latest` returns a single object instead of an array, so the JSONPath
loses the `[*]`:

| Field | Value |
|---|---|
| Feed URL | `http://localhost:8080/series/<code segments>/points/latest` |
| Date path | `$.date` |
| Date format | `yyyy-MM-dd` |
| Closing price path | `$.value` |

## Verifying

Use Portfolio Performance's own "Show server response" button in the feed editor, or check the raw
feed yourself first:

```bash
curl -sS "http://localhost:8080/series/TD/LFT/2031-03-01/SELL/points/latest"
# -> {"date":"2026-08-21","value":19638.10,"extra_values":{...},"source_updated_at":"..."}
```

If "Show server response" shows `null` instead of data, the feed URL itself is fine but the
Date/Price JSONPath fields still hold a stale expression (commonly leftover from a previous
provider's response shape, e.g. one that filtered by a `Simbolo` field brindex-api's response
doesn't have) — update them to the paths above.

## Keeping the server running

Portfolio Performance needs the API reachable whenever it refreshes quotes, not just while you're
actively testing — run it as a long-lived background process (or a systemd user service) rather
than a `./gradlew run` you close when done:

```bash
BRINDEX_DB_PATH=/path/to/brindex.sqlite nohup ./gradlew run > /tmp/brindex-api.log 2>&1 &
disown
```

To stop it later, don't rely on the disowned PID — `./gradlew run` forks the actual server as a
child of the Gradle daemon, not of the process you just backgrounded, so killing that PID alone can
leave the server (and port 8080) running. Kill by port instead:

```bash
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill
```

## Data gaps

The price history only goes as far as `brindex-ingest` has actually ingested — if the latest date
in Portfolio Performance looks stale, that's a `brindex-ingest` data-freshness question, not
something to fix here. Check a series' actual range directly:

```bash
curl -sS "http://localhost:8080/series/<code segments>/points" | python3 -c \
  "import json,sys; p=json.load(sys.stdin); print(p[0]['date'], '..', p[-1]['date'], f'({len(p)} points)')"
```
