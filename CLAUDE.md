# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Read-only HTTP API over BRIndex's historical series of official Brazilian market data (Tesouro
Direto bond prices, PTAX, CDI). It serves the SQLite database that a separate, independently
deployable repo, [`brindex-ingest`](https://github.com/marcosjunqueira/brindex-ingest), writes to.
The two repos share no code — only the database file and the `code` identity scheme documented in
`.specs/New/SPEC_READ_API.md`. This API never writes to the database and never invents or
reinterprets a `code`; it only looks entries up by the exact string `brindex-ingest` assigned.

## Language policy

All code, comments, identifiers, commit messages, and documentation in this repo are written in
English. The only exceptions:

- Proper nouns for the Brazilian financial instruments this API serves data about — Tesouro Direto,
  PTAX, CDI — stay as-is, not translated.
- Notes documenting the mapping to `brindex-ingest`'s SQLite column/value names, where those names
  are themselves Portuguese (see naming note below) — quoting the actual schema is not the same as
  writing prose in Portuguese.

Chat with the user may be in Portuguese; anything committed to the repo is not.

**Naming note:** this repo's spec and code use English names throughout (`domain`, `code`, `points`,
`value`, `name`, `metadata`, `created_at`, `extra_values`, `source_updated_at`), matching
`brindex-ingest`'s own schema, which underwent the same Portuguese→English rename (see that repo's
`.specs/New/SPEC_INGESTION.md` §3/§3.1). `series.domain` value is `treasury-direct`; PTAX codes are
`PTAX:USD:BUY`/`PTAX:USD:SELL`; Tesouro Direto and CDI codes are language-neutral.

Early scaffold: only `GET /health` is implemented. See `.specs/New/SPEC_READ_API.md` for the full
design (data model, planned endpoints, non-goals, decimal-handling discipline) before implementing
any new endpoint — it is the source of truth, not this file.

## Commands

```bash
./gradlew run        # start the server on :8080
./gradlew test        # run all tests
./gradlew test --tests "br.com.brindex.api.ApplicationTest"   # single test class
./gradlew test --tests "*.ApplicationTest.health endpoint responds ok"  # single test method
```

Requires JDK 25 (set via Gradle toolchain, no local JDK install needed if using the wrapper).

## Architecture

- Kotlin + Ktor (Netty engine), single module, entry point `src/main/kotlin/br/com/brindex/api/Application.kt`.
- `embeddedServer` is wired in `main()`; routes are registered in `Application.module()`, which is
  also what tests call directly via `testApplication { application { module() } }` — keep route
  logic reachable through `module()` so it stays testable without a running server.
- SQLite via `sqlite-jdbc`, reading the same database file `brindex-ingest` populates. Two tables,
  `series` and `points` (English names — see naming note above), described in full in
  `.specs/New/SPEC_READ_API.md` §2.
- **Decimal discipline**: `value` and any numeric field inside `extra_values`/`metadata` are
  stored as SQLite `TEXT`, never as float, to avoid round-off entering the historical record. The
  API's serialization layer must preserve this — never coerce a `NULL` price to `0` or drop the
  field; a missing price must stay distinguishable from a zero price. This is the one non-obvious
  invariant worth defending in review for any new endpoint touching `points`.
- **No auth, no write endpoints** — both explicit non-goals for v1 (personal use, private network).
  Don't add either speculatively.
- Tests use Ktor's `testApplication` + a fixture-seeded database, never a live `brindex-ingest` run
  — mirrors the fixture-only discipline `brindex-ingest` uses for its own parser tests.

## Spec workflow

`.specs/` tracks design docs through a lifecycle via directory: `New/` → `Ready/` → `Active/` →
`Archived/` (or `Blocked/`). `SPEC_READ_API.md` is currently in `New/` — it describes the target
design but says the response shape, error format, and pagination approach for the data endpoints
are still open decisions for the first implementation PR, not settled by the doc.
