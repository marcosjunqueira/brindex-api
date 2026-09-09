#!/usr/bin/env bash
# Builds a fixture SQLite DB, launches brindex-api against it, hits every
# endpoint with curl, then tears everything down. Run from the repo root
# (the directory containing build.gradle.kts).
set -euo pipefail

PORT="${PORT:-8080}"
WORKDIR="$(mktemp -d)"
DB_PATH="$WORKDIR/fixture.sqlite"
LOG_PATH="${LOG_PATH:-/tmp/brindex-api.log}"

cleanup() {
  # Kill the specific PID captured right after our own health check passed (see below) — not
  # "whatever is on $PORT at exit time", which could by then be an unrelated process (another dev
  # server, a stuck prior run) if ours already died. Narrows, but doesn't eliminate, the ownership
  # risk of a port-based kill; there's no cheaper way to find the real listener, since `./gradlew
  # run &` backgrounds the Gradle wrapper, not the forked server JVM, so `$!` doesn't identify it.
  if [[ -n "${OWNED_SERVER_PID:-}" ]] && kill -0 "$OWNED_SERVER_PID" 2>/dev/null; then
    kill "$OWNED_SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "== Seeding fixture DB at $DB_PATH =="
python3 - "$DB_PATH" <<'PY'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
cur = con.cursor()
cur.executescript("""
CREATE TABLE series (
  code          TEXT PRIMARY KEY,
  domain        TEXT NOT NULL,
  name          TEXT NOT NULL,
  metadata      TEXT NOT NULL,
  created_at    TEXT NOT NULL
);
CREATE TABLE points (
  series_code   TEXT NOT NULL REFERENCES series(code),
  date          TEXT NOT NULL,
  value         TEXT,
  extra_values  TEXT,
  source_updated_at TEXT NOT NULL,
  PRIMARY KEY (series_code, date)
);
""")
cur.executemany(
    "INSERT INTO series VALUES (?, ?, ?, ?, ?)",
    [
        ("TD:LFT:2026-03-01:BUY", "treasury-direct", "Tesouro Direto LFT 2026-03-01 (BUY)",
         '{"maturity":"2026-03-01","series":"LFT","side":"BUY"}', "2026-09-09T00:00:00Z"),
        ("PTAX:USD:SELL", "ptax", "PTAX USD Sell", "{}", "2026-09-09T00:00:00Z"),
    ],
)
cur.executemany(
    "INSERT INTO points VALUES (?, ?, ?, ?, ?)",
    [
        ("TD:LFT:2026-03-01:BUY", "2026-01-02", "18105.30",
         '{"rate":"0.000164","base_price":"18094.98"}', "2026-09-09T00:00:00Z"),
        ("TD:LFT:2026-03-01:BUY", "2026-01-06", "1234.5678901234", None, "2026-09-09T00:00:00Z"),
    ],
)
con.commit()
con.close()
print("seeded 2 series, 2 points")
PY

echo "== Building =="
./gradlew -q compileKotlin

echo "== Starting server on :$PORT (log: $LOG_PATH) =="
BRINDEX_DB_PATH="$DB_PATH" ./gradlew -q run > "$LOG_PATH" 2>&1 &

echo "== Waiting for /health =="
for i in $(seq 1 60); do
  if curl -sf "http://localhost:$PORT/health" > /dev/null; then
    OWNED_SERVER_PID="$(lsof -ti:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1)"
    break
  fi
  sleep 1
  if [[ "$i" -eq 60 ]]; then
    echo "server never became healthy, log follows:" >&2
    cat "$LOG_PATH" >&2
    exit 1
  fi
done

echo "== GET /health =="
curl -sS "http://localhost:$PORT/health"; echo

echo "== GET /series =="
curl -sS "http://localhost:$PORT/series"; echo

echo "== GET /series?domain=ptax =="
curl -sS "http://localhost:$PORT/series?domain=ptax"; echo

echo "== GET /series/{code}/points (code URL-encoded) =="
curl -sS "http://localhost:$PORT/series/TD%3ALFT%3A2026-03-01%3ABUY/points"; echo

echo "== GET /series/{code}/points/latest =="
curl -sS "http://localhost:$PORT/series/TD%3ALFT%3A2026-03-01%3ABUY/points/latest"; echo

echo "== GET /series/{unknown}/points -> expect 404 =="
curl -sS -o /dev/null -w "status: %{http_code}\n" "http://localhost:$PORT/series/DOES%3ANOT%3AEXIST/points"

echo "== smoke test passed =="
