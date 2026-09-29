# Deploying brindex-api and brindex-admin behind Cloudflare + Traefik

Step-by-step production setup for the whole stack on one Docker host. For the full go-live checklist
(ingestion, Stripe live mode, smoke tests, backups, monitoring, rollback) see [`GO_LIVE.md`](GO_LIVE.md).

```
client ──HTTPS──> Cloudflare ──HTTPS + client cert──> Traefik :443 ──> brindex-api   :8080
                                                                  └──> brindex-admin :8081 (public billing)
you ──SSH tunnel──> 127.0.0.1:8082 ──> brindex-admin admin page (never through Traefik)
```

Who limits what:

| Layer | Limit |
|---|---|
| Traefik | Per client IP (real IP from Cloudflare's `CF-Connecting-IP`): stops floods, including requests with random/invalid keys, before they reach the app |
| brindex-api | Per API key (`RATE_LIMIT_PER_MINUTE`): the subscriber's quota |

Replace `example.com` with your domain throughout. Assumed hostnames: `api.example.com` (brindex-api)
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

## 2. Build the apps

No images are published; build the distributions on the server (JDK 25):

```bash
git clone https://github.com/marcosjunqueira/brindex-api.git   && (cd brindex-api   && ./gradlew installDist)
git clone https://github.com/marcosjunqueira/brindex-admin.git && (cd brindex-admin && ./gradlew installDist)
```

Each lands in `build/install/<name>/` and is run by a plain JRE container below.

## 3. Traefik: trust only Cloudflare

Download Cloudflare's origin-pull CA next to the compose file:

```bash
mkdir -p traefik
curl -fsSL -o traefik/cloudflare-origin-pull-ca.pem \
  https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
```

`traefik/dynamic.yml`:

```yaml
tls:
  options:
    cloudflare:
      minVersion: VersionTLS12
      clientAuth:
        caFiles:
          - /etc/traefik/cloudflare-origin-pull-ca.pem
        clientAuthType: RequireAndVerifyClientCert

http:
  middlewares:
    # ~300 req/min per client IP, bursts up to 30. CF-Connecting-IP is set by Cloudflare on every
    # request and can't be spoofed because only Cloudflare can connect (client cert above).
    per-ip-ratelimit:
      rateLimit:
        average: 5
        period: 1s
        burst: 30
        sourceCriterion:
          requestHeaderName: CF-Connecting-IP
    per-ip-inflight:
      inFlightReq:
        amount: 10
        sourceCriterion:
          requestHeaderName: CF-Connecting-IP
```

Tune `average`/`burst` to taste. Portfolio Performance fetches one series per request, so a user
refreshing a large portfolio can make a few dozen requests in a burst.

## 4. docker-compose.yml

```yaml
services:
  traefik:
    image: traefik:v3
    command:
      - --providers.docker=true
      - --providers.docker.exposedbydefault=false
      - --providers.file.filename=/etc/traefik/dynamic.yml
      - --entrypoints.websecure.address=:443
      - --certificatesresolvers.le.acme.email=you@example.com
      - --certificatesresolvers.le.acme.storage=/letsencrypt/acme.json
      - --certificatesresolvers.le.acme.dnschallenge.provider=cloudflare
      # Access logs would record ?api_key=... (Portfolio Performance sends the key in the URL).
      # Either leave access logs off, or enable them and drop the path:
      # - --accesslog=true
      # - --accesslog.fields.names.RequestPath=drop
    environment:
      - CF_DNS_API_TOKEN=${CF_DNS_API_TOKEN}
    ports:
      - "443:443"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./traefik:/etc/traefik:ro
      - ./letsencrypt:/letsencrypt
    restart: unless-stopped

  brindex-api:
    image: eclipse-temurin:25-jre
    command: /app/bin/brindex-api
    environment:
      - BRINDEX_DB_PATH=/data/brindex.sqlite
      - ACCOUNTS_DB_PATH=/data/accounts.sqlite
      - REQUIRE_API_KEY=true
      - RATE_LIMIT_PER_MINUTE=60
    volumes:
      - ./brindex-api/build/install/brindex-api:/app:ro
      # Read-write on purpose: SQLite must be able to roll back a journal left by an interrupted
      # ingest run. The API still never writes (query_only).
      - ./data:/data
    labels:
      - traefik.enable=true
      - traefik.http.routers.api.rule=Host(`api.example.com`)
      - traefik.http.routers.api.entrypoints=websecure
      - traefik.http.routers.api.tls.certresolver=le
      - traefik.http.routers.api.tls.options=cloudflare@file
      - traefik.http.routers.api.middlewares=per-ip-ratelimit@file,per-ip-inflight@file
      - traefik.http.services.api.loadbalancer.server.port=8080
    restart: unless-stopped

  brindex-admin:
    image: eclipse-temurin:25-jre
    command: /app/bin/brindex-admin
    env_file: brindex-admin.env   # ADMIN_PASSWORD, STRIPE_* (see brindex-admin/.env.example)
    environment:
      - ACCOUNTS_DB_PATH=/data/accounts.sqlite
      - PUBLIC_BASE_URL=https://billing.example.com
      # Bind inside the container; the host-side port mapping below keeps it on localhost.
      - ADMIN_HOST=0.0.0.0
    volumes:
      - ./brindex-admin/build/install/brindex-admin:/app:ro
      - ./data:/data
    ports:
      - "127.0.0.1:8082:8082"   # admin page: host localhost only, never routed by Traefik
    labels:
      - traefik.enable=true
      - traefik.http.routers.billing.rule=Host(`billing.example.com`)
      - traefik.http.routers.billing.entrypoints=websecure
      - traefik.http.routers.billing.tls.certresolver=le
      - traefik.http.routers.billing.tls.options=cloudflare@file
      - traefik.http.routers.billing.middlewares=per-ip-ratelimit@file,per-ip-inflight@file
      - traefik.http.services.billing.loadbalancer.server.port=8081
    restart: unless-stopped
```

`./data` holds both SQLite files: `brindex.sqlite` (written by the brindex-ingest cron job) and
`accounts.sqlite` (created by brindex-admin on first start). Start brindex-admin once before turning
on `REQUIRE_API_KEY`, since brindex-api refuses to start without the accounts database.

```bash
echo "CF_DNS_API_TOKEN=..." > .env
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
