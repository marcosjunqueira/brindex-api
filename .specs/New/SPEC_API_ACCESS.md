# SPEC: Paid API access (API keys, Stripe subscription, admin)

**Status:** New — the `brindex-api` side (key check + rate limit) is implemented in the PR that adds
this file; the admin/billing service is not built yet
**Date:** 2026-09-28
**Repo:** `brindex-api` (key check) + a separate admin/billing service (everything else)
**Related:** [`SPEC_READ_API.md`](SPEC_READ_API.md) (the endpoints being protected)

---

## 1. Purpose

Sell access to the read API as one cheap monthly Stripe subscription, with the smallest amount of
code that is still safe to expose on the internet.

`brindex-api` is public, so it holds nothing administrative: it only checks keys. Issuing keys,
Stripe and the admin page live in a separate service that owns the accounts database — the same
split `brindex-ingest` already uses for the data database (one writer, the API only reads).

With `REQUIRE_API_KEY` unset the API behaves exactly as before (no auth, private network).

## 2. Split of responsibilities

| | `brindex-api` (public) | admin/billing service |
|---|---|---|
| Accounts DB | read-only (`query_only`, no CREATE) | owns schema, only writer |
| API keys | checks `Authorization: Bearer brx_...` (or `?api_key=`, for Portfolio Performance) on `/series...` | generates, shows once, stores hash |
| Rate limit | per key, in memory, `429` + `Retry-After` | — |
| Stripe | none (no SDK, no secrets) | Checkout, webhook, portal link |
| Admin UI | none | list, complimentary key, rotate, disable |

## 3. Accounts database contract

The only coupling between the two, like the ingest database's `series`/`points`. `brindex-api`
depends only on these columns:

```sql
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    key_hash TEXT UNIQUE,        -- lowercase hex SHA-256 of the full key string; NULL = no key yet
    status TEXT NOT NULL,        -- Stripe subscription status, or 'active' for complimentary keys
    disabled INTEGER NOT NULL DEFAULT 0,  -- admin kill switch, independent of Stripe
    -- the admin service adds whatever else it needs (email, stripe ids, timestamps, ...)
);
```

A key has access when `disabled = 0` and `status` ∈ {`active`, `trialing`, `past_due`}
(`past_due`: Stripe is still retrying the card). Anything else → `401`.

Keys: 32 random bytes, base64url, `brx_` prefix. SHA-256 without salt is enough for 256-bit random
tokens. Plain keys are never stored; a lost key is rotated.

The admin service must write with upserts/single statements (never delete-then-insert) so a
concurrent key check never sees a missing row.

## 4. Admin/billing service (to build)

Kept as small as possible:

1. `GET /subscribe` → Stripe Checkout (subscription mode, one `STRIPE_PRICE_ID`). Price, currency
   and trial live in Stripe, so changing them needs no deploy.
2. `GET /subscribe/success?session_id=cs_...` → retrieves the session from Stripe, upserts the
   account by customer id, issues the key **once** (reload/replay shows "already issued").
3. `POST /webhooks/stripe` for `customer.subscription.created|updated|deleted` → verifies
   `Stripe-Signature`, re-reads the subscription status from Stripe (events arrive out of order),
   stores it.
4. Customer self-service: Stripe's hosted no-code portal login link (cancel, card, invoices).
5. `/admin`: listed accounts, complimentary key, rotate, disable/enable. Only reachable on the
   private network (separate port bound to a private interface, or VPN), never through the public
   proxy; plus its own auth.
6. Live Stripe keys refused unless explicitly enabled; start in test mode.

Routes 1–3 must be public (buyers and Stripe reach them); route 5 must not.

## 5. Non-goals (for now)

- Multiple plans/tiers or monthly usage quotas (one price, one rate limit).
- Self-service key rotation or lost-key recovery (admin rotates).
- A shared/distributed rate limiter (single `brindex-api` instance; resets on restart).
