# Go-live runbook

Ordered checklist to take the Brindex stack (brindex-api, brindex-admin, brindex-ingest) to
production with paying customers. It does not repeat the infrastructure setup: that lives in
[`DEPLOY.md`](DEPLOY.md) (Cloudflare, Traefik, docker-compose, admin over SSH tunnel), and each step
below links to the section it relies on. Portuguese version: [`GO_LIVE.pt-BR.md`](GO_LIVE.pt-BR.md).

Replace `example.com` with your domain, as in `DEPLOY.md`. Never paste real keys or secrets into this
file, a commit, a ticket or a chat: they go only in the server's env files.

## 0. Open item: B3 redistribution terms (blocks selling B3 data)

Serving B3 COTAHIST data through a paid API is redistribution. B3's *Política Comercial de Market
Data* (market data commercial policy) most likely requires a contract for that. This was **inferred
from the policy's description, not verified** against the document itself (see brindex-ingest's
`.specs/New/SPEC_INGESTION.md` §2.2).

Until B3 confirms in writing, either keep `b3` out of the ingestion (see step 3) or do not sell
subscriptions. Tesouro Direto (ODbL), PTAX and CDI are open data.

## 1. Server prerequisites

- [ ] A Linux host with Docker + Docker Compose and the `sqlite3` CLI (for backups). Nothing is
      built on the server: every service runs from its GHCR image.
- [ ] Domain on Cloudflare, set up per [`DEPLOY.md` §1](DEPLOY.md#1-cloudflare-dns-and-tls)
      (Full strict, Authenticated Origin Pulls, DNS token).
- [ ] Images and deploy files per [`DEPLOY.md` §2](DEPLOY.md#2-get-the-images), Traefik per
      [§3](DEPLOY.md#3-traefik-trust-only-cloudflare), compose file per
      [§4](DEPLOY.md#4-docker-composeyml). Don't `docker compose up` yet.
- [ ] Only port 443 (and SSH) reachable from outside. Nothing listens publicly on 8080/8081/8082.
- [ ] Pin the released version of each image in `.env` (`BRINDEX_*_VERSION`) and keep a note of the
      previous ones: you need them to roll back. Releases: [`DEPLOY.md` §8](DEPLOY.md#8-releasing).

## 2. Environment variables

Each service documents its variables in its own `.env.example`; this is what production needs.

| Service | Where | Variables |
|---|---|---|
| brindex-api | `environment:` in compose | `BRINDEX_DB_PATH=/data/brindex.sqlite`, `ACCOUNTS_DB_PATH=/data/accounts.sqlite`, `REQUIRE_API_KEY=true`, `RATE_LIMIT_PER_MINUTE` (default 60). Optional: `POINTS_MAX_ROWS`, `CORS_ALLOWED_ORIGINS`. See [`.env.example`](../.env.example). |
| brindex-admin | `brindex-admin.env` (mode `600`, never committed) | `ADMIN_USER`, `ADMIN_PASSWORD` (≥ 16 chars), `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_ID`, `STRIPE_PORTAL_LOGIN_URL`, `STRIPE_LIVE_MODE` (step 5). `ACCOUNTS_DB_PATH`, `PUBLIC_BASE_URL`, `ADMIN_HOST` are set in compose. See brindex-admin's `.env.example`. |
| brindex-ingest | `environment:` in compose | `BRINDEX_DB_PATH=/data/brindex.sqlite`. The source is a command-line argument (step 3). |
| Compose | `.env` next to compose ([`deploy/.env.example`](../deploy/.env.example)) | `BRINDEX_API_VERSION`, `BRINDEX_ADMIN_VERSION`, `BRINDEX_INGEST_VERSION`, `BRINDEX_UID`, `BRINDEX_GID`, `API_HOST`, `BILLING_HOST`, `ACME_EMAIL`, `CF_DNS_API_TOKEN` |

- [ ] `brindex-api` and `brindex-admin` point at the **same** `accounts.sqlite`.
- [ ] `brindex-api` and `ingest` point at the **same** `brindex.sqlite`.
- [ ] `./data` is owned by `BRINDEX_UID:BRINDEX_GID` (the deploy user).
- [ ] Env files are `chmod 600` and owned by the deploy user.

## 3. Ingestion: first load and daily schedule

brindex-api refuses to start until `brindex.sqlite` has its tables, so load data first.

Run from the compose directory (`/srv/brindex` in `DEPLOY.md`). The `ingest` job writes
`./data/brindex.sqlite` as `BRINDEX_UID` and exits; arguments after `ingest` go to `brindex-ingest`.
Always start it under `flock ingest.lock`: two runs at once (say, a manual backfill and the cron
job) would compete for the database lock and one of them would fail with `database is locked`.
With `flock`, the second run waits for the first to finish.

```bash
cd /srv/brindex
# First load: backfill history. B3 downloads one ~80 MB file per year.
flock ingest.lock docker compose run --rm ingest --since 2020-01-01 --since-year 2020
echo "exit code: $?"    # 0 only if every source succeeded
```

`--source` takes one value (`all`, the default, or a single source). To leave `b3` out until step 0
is settled, run the other three instead:

```bash
for s in treasury-direct ptax cdi; do flock ingest.lock docker compose run --rm ingest --since 2020-01-01 --since-year 2020 --source $s; done
```

Each source fails independently; a non-zero exit names the failed sources in the log. Re-running is
safe (upserts), so just run it again after a transient outage.

Daily schedule (cron as the deploy user, 22:00 server time, after B3 publishes the day's close):

```cron
0 22 * * * cd /srv/brindex && flock -w 7200 ingest.lock docker compose run --rm ingest >> /var/log/brindex-ingest.log 2>&1
```

(`-w 7200` waits up to two hours for a manual run to finish, then gives up with a non-zero exit.
Without `b3`: one line per source, each ending in `run --rm ingest --source <source>`.)

- [ ] First load finished with exit code 0.
- [ ] Cron entry installed; the deploy user is in the `docker` group and can write the log file.
- [ ] Optional: append `&& curl -fsS https://hc-ping.com/<uuid>` (or any dead-man's-switch service)
      so a missing or failed run alerts you.

## 4. Start the services with `REQUIRE_API_KEY=true`

1. Start brindex-admin first, so it creates `accounts.sqlite` (brindex-api refuses to start without
   it when `REQUIRE_API_KEY=true`):
   ```bash
   docker compose up -d traefik brindex-admin
   ```
2. Then brindex-api (compose already has `REQUIRE_API_KEY=true`):
   ```bash
   docker compose up -d brindex-api
   ```
3. Through the SSH tunnel ([`DEPLOY.md` §6](DEPLOY.md#6-using-the-admin-page)), create a
   complimentary key for yourself on `/admin`. You need it for the smoke tests and monitoring.

- [ ] Never run production with `REQUIRE_API_KEY=false`: the data routes would be open to anyone.

## 5. Stripe: switch to live mode

Test and live mode are separate worlds in Stripe: products, prices, webhooks, the customer portal
and keys all have to be created again in live mode. Steps mirror brindex-admin's README
("Stripe setup (test mode)"), in **live mode**:

- [ ] Account activated for live payments (business details and bank account in the Dashboard).
- [ ] Products → the monthly product and recurring price, created in live mode → `STRIPE_PRICE_ID`.
- [ ] Developers → API keys → live secret key (`sk_live_...`) → `STRIPE_SECRET_KEY`. Prefer a
      restricted key if you set one up, and store it only in `brindex-admin.env`.
- [ ] Developers → Webhooks → live endpoint `https://billing.example.com/webhooks/stripe` with
      `customer.subscription.created`, `.updated`, `.deleted` → signing secret →
      `STRIPE_WEBHOOK_SECRET`. If deliveries get 403, see [`DEPLOY.md` §5](DEPLOY.md#5-stripe-webhook-through-cloudflare).
- [ ] Settings → Billing → Customer portal, configured in live mode → `STRIPE_PORTAL_LOGIN_URL`.
- [ ] `STRIPE_LIVE_MODE=true` in `brindex-admin.env` (without it brindex-admin refuses a live key).
- [ ] `docker compose up -d --force-recreate brindex-admin`.
- [ ] **Rotate the test secret key that was pasted in chat**: Dashboard in test mode → Developers →
      API keys → *Roll key*. Also roll the test webhook signing secret if it was shared. Update any
      local `.env` that still uses them.

## 6. Smoke tests

Run from a machine outside the server. `$KEY` is the complimentary key from step 4 (keep it out of
shell history: `read -s KEY`).

```bash
API=https://api.example.com

# Liveness and readiness (no key needed).
curl -s -o /dev/null -w '%{http_code}\n' $API/health    # 200
curl -s -o /dev/null -w '%{http_code}\n' $API/ready     # 200

# 401 without a key, 200 with one.
curl -s -o /dev/null -w '%{http_code}\n' $API/series                                   # 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $KEY" $API/series   # 200

# Data is current: the date should be the last business day.
curl -s -H "Authorization: Bearer $KEY" $API/v1/series/PTAX/USD/SELL/points/latest

# 429 from the per-key limit: ~70 requests in under a minute, paced at 4/s so Traefik's
# per-IP limit (5/s) doesn't answer first. Expect 200s, then 429 after RATE_LIMIT_PER_MINUTE.
for i in $(seq 1 70); do
  curl -s -o /dev/null -w '%{http_code} ' -H "Authorization: Bearer $KEY" $API/series; sleep 0.25
done; echo
```

Then the rest of [`DEPLOY.md` §7](DEPLOY.md#7-verify): origin IP rejects the TLS handshake, the
per-IP burst returns 429, and `https://billing.example.com/admin` is 404.

End-to-end purchase in live mode:

- [ ] Open `https://billing.example.com/subscribe`, pay with a real card, get the key once.
- [ ] That key returns 200 on `/series`; the account shows as `active` on `/admin`.
- [ ] Cancel it through the portal link; after the webhook arrives the key returns 401. Refund
      the charge in the Dashboard.

## 7. Backups

`brindex.sqlite` can be rebuilt from the sources (step 3), slowly. `accounts.sqlite` **cannot**: it
holds every customer's key hash and subscription link. Back up both, daily, with SQLite's online
backup (safe while the services run; never `cp` a live SQLite file):

```bash
#!/usr/bin/env bash
# /path/to/backup_brindex.sh
set -euo pipefail
DATA=/srv/brindex/data
OUT=/path/to/backups
DAY=$(date +%F)
sqlite3 "$DATA/accounts.sqlite" ".backup '$OUT/accounts-$DAY.sqlite'"
sqlite3 "$DATA/brindex.sqlite"  ".backup '$OUT/brindex-$DAY.sqlite'"
find "$OUT" -name '*.sqlite' -mtime +14 -delete    # keep two weeks locally
```

```cron
30 23 * * * /path/to/backup_brindex.sh >> /var/log/brindex-backup.log 2>&1
```

- [ ] Backups copied off the server (another host or object storage), encrypted if it is a third
      party: `accounts.sqlite` holds customer emails and Stripe ids.
- [ ] Restore tested once: `sqlite3 accounts-<day>.sqlite 'select count(*) from accounts'`.

## 8. Monitoring

- [ ] External uptime check on `https://api.example.com/ready` (every 1–5 min). `/ready` returns 503
      when the database can't be queried; `/health` only says the process is up.
- [ ] Uptime check on `https://billing.example.com/health`.
- [ ] Ingestion alert from step 3 (dead-man's switch or reading the cron log).
- [ ] Stripe → Developers → Webhooks: enable email alerts for failing deliveries.
- [ ] Disk space alert on the data volume (COTAHIST backfills and backups grow it).
- [ ] Healthchecks: `docker compose ps` shows brindex-api and brindex-admin as `healthy`.
- [ ] Logs: `docker compose logs -f brindex-api brindex-admin`. Keep Traefik access logs off or
      with `RequestPath` dropped ([`DEPLOY.md` §4](DEPLOY.md#4-docker-composeyml)): the path can
      carry `?api_key=`.

## 9. Rollback

| Problem | Action |
|---|---|
| Bad release of brindex-api or brindex-admin | Set the previous version in `.env` (`BRINDEX_API_VERSION` / `BRINDEX_ADMIN_VERSION`) and `docker compose up -d <service>`. |
| Bad ingestion (wrong values) | Set the previous `BRINDEX_INGEST_VERSION` (or release a fix) and re-run `flock ingest.lock docker compose run --rm ingest` for the affected range: upserts overwrite. If that isn't enough, stop brindex-api, restore `brindex-<day>.sqlite` over `data/brindex.sqlite`, start it again. |
| `accounts.sqlite` damaged | Stop brindex-admin and brindex-api, restore the latest `accounts-<day>.sqlite`, start both. Subscriptions changed since then are corrected by the next Stripe webhook for that customer; check the Dashboard for anything newer than the backup. |
| Stop selling (e.g. B3 says no) | Remove `STRIPE_SECRET_KEY` from `brindex-admin.env` and recreate brindex-admin: `/subscribe` and the webhook turn off, existing keys keep working. Pause or cancel subscriptions in the Dashboard. To drop B3 data from the API, stop ingesting `b3` (step 3) and rebuild `brindex.sqlite` without it. |
| Leaked Stripe key | Roll it in the Dashboard, update `brindex-admin.env`, recreate brindex-admin. |

Never roll back by setting `REQUIRE_API_KEY=false`.
