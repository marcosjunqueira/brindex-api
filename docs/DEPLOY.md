# Deploying brindex-api and brindex-admin behind Cloudflare + Traefik

Step-by-step production setup for the whole stack on one Docker host. For the full go-live checklist
(ingestion, Stripe live mode, smoke tests, backups, monitoring, rollback) see [`GO_LIVE.md`](GO_LIVE.md).

```
client ──HTTPS──> Cloudflare ──HTTPS + client cert──> Traefik :443 ──> brindex-api   :8080
                                                                  └──> brindex-admin :8081 (public billing)
you ──SSH tunnel──> 127.0.0.1:8082 ──> brindex-admin admin page (never through Traefik)
host cron ──> flock ingest.lock docker compose run --rm ingest ──> ./data/brindex.sqlite (only writer)
```

Who limits what:

| Layer | Limit |
|---|---|
| Traefik | Per client IP (real IP from Cloudflare's `CF-Connecting-IP`): stops floods, including requests with random/invalid keys, before they reach the app |
| brindex-api | Per API key (`RATE_LIMIT_PER_MINUTE`): the subscriber's quota |

Replace `example.com` with your domain throughout (in `.env`, `API_HOST`/`BILLING_HOST`). Assumed hostnames: `api.example.com` (brindex-api)
and `billing.example.com` (brindex-admin's public side).

## 1. Cloudflare DNS and TLS

1. DNS → add `A` records for `api` and `billing` pointing at the server, **Proxied** (orange cloud).
2. SSL/TLS → Overview → encryption mode **Full (strict)**.
3. SSL/TLS → Origin Server → turn on **Authenticated Origin Pulls**. Cloudflare will then present a
   client certificate to your origin, and step 3 makes Traefik reject any connection without it. This
   is what stops anyone from bypassing Cloudflare by hitting the server's IP directly, and it is why
   `CF-Connecting-IP` can be trusted below. (A firewall allow-list is not enough on its own: ports
   published by Docker bypass `ufw`.)
4. My Profile → API Tokens → create a token with **Zone → DNS → Edit** for this zone only. Traefik
   uses it for the Let's Encrypt DNS challenge.
5. Caching: leave the default rules. Don't add a "Cache Everything" rule for `api.example.com`: every
   response depends on the API key.

## 2. Get the images

Each repo publishes a Docker image to GHCR on every release (see [Releasing](#8-releasing)):

| Image | Runs as | Listens on |
|---|---|---|
| `ghcr.io/marcosjunqueira/brindex-api` | long-running service | `:8080` (healthcheck on `/ready`) |
| `ghcr.io/marcosjunqueira/brindex-admin` | long-running service | `:8081` public (healthcheck on `/health`), `:8082` admin page |
| `ghcr.io/marcosjunqueira/brindex-ingest` | one-shot job run by host cron | nothing |

Each release is tagged `X.Y.Z`; the floating `X.Y`, `X` (from 1.0.0 on) and `latest` tags move to it
only when it is the highest release in that range. Pin the exact `X.Y.Z` in
production so a restart never changes what runs. The images run as a non-root user and carry no
secrets or data: configuration comes from the environment and the SQLite files from the `./data`
bind mount.

brindex-admin's repo is private, so its package is too: log the Docker host in once with a GitHub
personal access token (classic) that has only `read:packages`:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u marcosjunqueira --password-stdin
```

(After the first release, check each package's visibility under the repo's *Packages*. A public
brindex-api/brindex-ingest package can be pulled without logging in.)

The deploy files live in this repo under [`deploy/`](../deploy): `docker-compose.yml`,
`traefik/dynamic.yml` and `.env.example`. If Traefik already runs in its own stack (e.g. Dockge with a shared `proxy`
network), use [`deploy/dockge/`](../deploy/dockge/README.md) instead. Copy that directory to the server, e.g.:

```bash
git clone --depth 1 https://github.com/marcosjunqueira/brindex-api.git /tmp/brindex-api
sudo cp -r /tmp/brindex-api/deploy /srv/brindex && sudo chown -R "$USER": /srv/brindex
cd /srv/brindex && cp .env.example .env && chmod 600 .env && mkdir -p data letsencrypt
```

## 3. Traefik: trust only Cloudflare

Download Cloudflare's origin-pull CA next to `traefik/dynamic.yml`:

```bash
curl -fsSL -o traefik/cloudflare-origin-pull-ca.pem \
  https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
```

[`traefik/dynamic.yml`](../deploy/traefik/dynamic.yml) holds only the `cloudflare` TLS option, which
makes Traefik require Cloudflare's client certificate. It stays in a file because Traefik can't define
TLS options with Docker labels.

The rate limits are Docker labels on the `traefik` service in the compose file, so they exist
whenever Traefik runs and both routers reference them as `@docker`:

| Middleware | Setting |
|---|---|
| `per-ip-ratelimit` | `average=5`, `period=1s`, `burst=30` per `CF-Connecting-IP` (~300 req/min per client IP) |
| `per-ip-inflight` | at most 10 concurrent requests per `CF-Connecting-IP` |

`CF-Connecting-IP` is set by Cloudflare on every request and can't be spoofed because only Cloudflare
can connect (client certificate above). Tune `average`/`burst` to taste. Portfolio Performance fetches
one series per request, so a user refreshing a large portfolio can make a few dozen requests in a
burst.

## 4. docker-compose.yml

[`deploy/docker-compose.yml`](../deploy/docker-compose.yml) runs the whole stack. Everything
host-specific comes from `.env` ([`deploy/.env.example`](../deploy/.env.example)):

| Variable | Meaning |
|---|---|
| `BRINDEX_API_VERSION`, `BRINDEX_ADMIN_VERSION`, `BRINDEX_INGEST_VERSION` | Image versions to run (`X.Y.Z`). Compose refuses to start if one is missing. |
| `BRINDEX_UID`, `BRINDEX_GID` | Host user that owns `./data` (`id -u`, `id -g`). |
| `API_HOST`, `BILLING_HOST` | Public hostnames, e.g. `api.example.com`, `billing.example.com`. |
| `ACME_EMAIL`, `CF_DNS_API_TOKEN` | Let's Encrypt account email and the Cloudflare token from §1. |

brindex-admin's own secrets (`ADMIN_PASSWORD`, `STRIPE_*`) go in `brindex-admin.env` next to it
(mode `600`, see brindex-admin's `.env.example`).

What the file sets up:

- **brindex-api** behind Traefik on `API_HOST`, with `REQUIRE_API_KEY=true`. It starts only after
  brindex-admin is healthy, because it refuses to start without `accounts.sqlite`.
- **brindex-admin**'s public listener (`:8081`) behind Traefik on `BILLING_HOST`. Its admin page
  (`:8082`) is published on the host's `127.0.0.1` only and has no Traefik router.
- **ingest**: a one-shot job in the `jobs` profile, so `docker compose up` never starts it. Host cron
  runs it with `flock ingest.lock docker compose run --rm ingest ...`, so a manual run and the cron
  job never overlap (see [`GO_LIVE.md` §3](GO_LIVE.md#3-ingestion-first-load-and-daily-schedule)).
  It is the only writer of `brindex.sqlite`.

`./data` holds both SQLite files: `brindex.sqlite` (written by the ingest job) and `accounts.sqlite`
(created by brindex-admin on first start). Every container that touches it runs as
`BRINDEX_UID:BRINDEX_GID`, so the files keep a single owner, the host user can back them up, and
brindex-api can write to the database file and its directory. It never writes data (`query_only`),
but SQLite must be able to roll back a journal left by an interrupted ingest run.

Access logs stay off: they would record `?api_key=...` (Portfolio Performance sends the key in the
URL). If you turn them on, drop the path (commented flags in the compose file).

```bash
docker compose pull
docker compose up -d
```

## 5. Stripe webhook through Cloudflare

Stripe → Developers → Webhooks → endpoint `https://billing.example.com/webhooks/stripe` (events in
brindex-admin's README). If deliveries fail with 403, Cloudflare's bot protection is challenging
Stripe: check Security → Events for the blocked request, and turn off Bot Fight Mode for the zone
(on the free plan it can't be skipped per path).

## 6. Using the admin page

```bash
ssh -L 8082:127.0.0.1:8082 you@server
# then open http://localhost:8082/admin
```

## 7. Verify

```bash
# Through Cloudflare: 401 without a key, 200 with one.
curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/series
curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer brx_...' https://api.example.com/series

# Straight to the origin IP, bypassing Cloudflare: must fail the TLS handshake (no client cert).
curl -sk --resolve api.example.com:443:<server-ip> https://api.example.com/health

# Per-IP limit: a burst past ~30 requests starts returning 429.
for i in $(seq 1 60); do curl -s -o /dev/null -w '%{http_code} ' https://api.example.com/health; done; echo

# Admin is not reachable publicly.
curl -s -o /dev/null -w '%{http_code}\n' https://billing.example.com/admin   # 404
```

## 8. Releasing

brindex-api, brindex-admin and brindex-ingest release the same way, with [SEMVER](https://semver.org)
tags on `main`:

1. Run `scripts/release.sh` from an up-to-date, clean `main` (each repo has its own copy):
   ```bash
   git switch main && git pull
   scripts/release.sh --dry-run patch   # checks and prints the plan; changes nothing
   scripts/release.sh patch             # or minor, major, or an explicit 1.2.3
   ```
   It refuses to run off `main`, with uncommitted changes, behind or ahead of `origin/main`, or
   when the tag already exists. After you type the tag to confirm, it sets the version (`version`
   in `build.gradle.kts`, or `pyproject.toml` for brindex-ingest), commits `Release 1.2.3` on
   `main`, tags it `v1.2.3` and pushes both at once. If the version was already bumped in a merged
   PR, pass that same version and it only tags.
2. By hand, the same thing is: bump the version on `main`, then tag that commit and push the tag:
   ```bash
   git tag v1.2.3 && git push origin v1.2.3
   ```
3. The `Release` workflow (`.github/workflows/release.yml`) then:
   - checks the tag is on `main` and matches the project version, then runs the build and tests
     (brindex-api also waits for CodeQL to pass on that commit);
   - builds the image and pushes it to `ghcr.io/marcosjunqueira/<repo>` as `1.2.3`, and as `1.2`,
     `1` and `latest` only when no higher release exists in that range, so a backport or a re-run
     of an old tag never moves them back (no bare `0` tag while the major version is 0). Releases
     run one at a time per repo;
   - creates the GitHub Release with notes generated from the merged PRs.

   A failed check stops the release before anything is published; fix it on `main` and tag a new
   patch version (never move a published tag).
4. Deploy: set the new version in the server's `.env`, then `docker compose pull && docker compose up -d`.
   Roll back the same way with the previous version.

The PR CI (`ci.yml`) is unchanged: it still builds and tests every PR and push to `main`.
