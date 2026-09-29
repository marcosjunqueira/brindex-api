# brindex on Dockge, behind an existing Traefik

Use this instead of [`../docker-compose.yml`](../docker-compose.yml) when Traefik already runs in its
own stack and publishes containers on the external Docker network `proxy`. Everything else in
[`docs/DEPLOY.md`](../../docs/DEPLOY.md) and [`docs/GO_LIVE.md`](../../docs/GO_LIVE.md) still applies
(Cloudflare, ingestion, backups, smoke tests).

What this stack expects from the Traefik stack:

| Item | Value |
|---|---|
| Docker network | `proxy` (external), `--providers.docker.exposedbydefault=false` |
| Entrypoint | `websecure` (:443) |
| ACME resolver | `myresolver` (Cloudflare DNS challenge) |
| File provider | defines the TLS option `cloudflare` (below) |

## Two API instances

The stack runs the brindex-api image twice, on the same data dir:

| Service | API key | Reachable from |
|---|---|---|
| `brindex-api-public` | required, plus the app's per-key limit and Traefik's per-IP limits | the internet, through Cloudflare and Traefik on `API_HOST` |
| `brindex-api` | none | containers on the Docker network `brindex-internal` only, as `http://brindex-api:8080` |

The keyless instance must never get Traefik labels, published ports, or the `proxy` network: anything
that can reach it reads the data without a key. Only trusted containers join `brindex-internal`.

To let another stack (e.g. Cornerstone) call it, deploy this stack first (it creates the network), then
add to that stack's compose:

```yaml
networks:
  brindex-internal:
    external: true

services:
  cornerstone:
    networks:
      - brindex-internal   # keep its other networks too, e.g. proxy
```

and point it at `http://brindex-api:8080`.

## 1. Traefik changes

### Accept only Cloudflare (required)

The per-IP limits key on `CF-Connecting-IP`. Anyone who reaches the origin directly can set that
header to any value and dodge the limits, so Traefik must accept connections from Cloudflare only.
Turn on **Authenticated Origin Pulls** in Cloudflare (SSL/TLS → Origin Server), then add the
`cloudflare` TLS option to the file the Traefik file provider already loads (merge into its existing
`tls:` key) and put Cloudflare's CA next to it:

```yaml
tls:
  options:
    cloudflare:
      minVersion: VersionTLS12
      clientAuth:
        caFiles:
          - /certs/cloudflare-origin-pull-ca.pem
        clientAuthType: RequireAndVerifyClientCert
```

```bash
curl -fsSL -o <certs dir>/cloudflare-origin-pull-ca.pem \
  https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
```

The option applies only to the routers that reference it (`tls.options=cloudflare@file`), so other
sites on the same Traefik are unaffected. Both hostnames must be proxied (orange cloud) in Cloudflare.

Trusting Cloudflare's ranges with `--entrypoints.websecure.forwardedHeaders.trustedIPs` is not a
substitute: it controls which `X-Forwarded-*` headers Traefik keeps, but does not stop a direct
connection from sending its own `CF-Connecting-IP`.

### Recommended

- `--api.insecure=true` with `8080:8080` publishes the Traefik dashboard and API with no auth on every
  host interface. Drop the port, or bind it to loopback: `127.0.0.1:8080:8080`.
- `--log.level=DEBUG` is verbose and can log request details; use `INFO` in production.
- Keep access logs off, or drop the path if you turn them on: Portfolio Performance sends the API key
  as `?api_key=...`.
  ```
  - --accesslog=true
  - --accesslog.fields.names.RequestPath=drop
  ```

## 2. Pull access to GHCR

brindex-admin's package is private. Create a classic GitHub token with only `read:packages` and log in
from the Docker client that pulls the images. Dockge runs `docker compose` inside its own container, so
log in there (the Dockge container name may differ):

```bash
docker exec -it dockge docker login ghcr.io -u marcosjunqueira
```

That login lives in the Dockge container and is lost if it is recreated. To keep it, log in on the
host (`docker login ghcr.io ...` as root) and mount `/root/.docker:/root/.docker:ro` into Dockge.

## 3. Create the stack

1. In Dockge, create a stack named `brindex`, paste [`compose.yaml`](compose.yaml) and fill the `.env`
   tab from [`.env.example`](.env.example).
2. On the host, in the stack directory (Dockge's stacks dir, `/opt/stacks/brindex` by default), create
   the data dir owned by `BRINDEX_UID:BRINDEX_GID`:
   ```bash
   mkdir -p data && chown 1000:1000 data
   ```
3. Deploy. brindex-admin starts first and creates `accounts.sqlite`; brindex-api-public starts
   once brindex-admin is healthy. The internal `brindex-api` needs no accounts database.

The admin page is at `http://127.0.0.1:8082/admin` on the server only; reach it with an SSH tunnel
(`ssh -L 8082:127.0.0.1:8082 <server>`). It has no Traefik router.

## 4. Ingestion

`ingest` is in the `jobs` profile, so Dockge never starts it. First load and daily cron, from the stack
directory (see `docs/GO_LIVE.md` §3 for backfill options):

```bash
cd /opt/stacks/brindex && flock ingest.lock docker compose run --rm ingest
```

```cron
0 22 * * * cd /opt/stacks/brindex && flock -w 7200 ingest.lock docker compose run --rm ingest >> /var/log/brindex-ingest.log 2>&1
```

Run it as a user that can reach the Docker socket and pull from GHCR (host login from §2).
