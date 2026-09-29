# Roteiro de go-live

Checklist ordenado para colocar o stack Brindex (brindex-api, brindex-admin, brindex-ingest) em
produção com clientes pagantes. Versão em português de [`GO_LIVE.md`](GO_LIVE.md); em caso de
divergência, vale a versão em inglês. A infraestrutura não é repetida aqui: ela está em
[`DEPLOY.md`](DEPLOY.md) (Cloudflare, Traefik, docker-compose, admin via túnel SSH), e cada passo
abaixo aponta a seção de que depende.

Troque `example.com` pelo seu domínio, como no `DEPLOY.md`. Nunca cole chaves ou segredos reais
neste arquivo, num commit, num ticket ou num chat: eles vão só nos arquivos de ambiente do servidor.

## 0. Pendência: termos de redistribuição da B3 (bloqueia a venda de dados B3)

Servir dados do COTAHIST da B3 numa API paga é redistribuição. A *Política Comercial de Market Data*
da B3 muito provavelmente exige contrato para isso. Isso foi **inferido pela descrição da política,
não verificado** no documento em si (veja `.specs/New/SPEC_INGESTION.md` §2.2 do brindex-ingest).

Até a B3 confirmar por escrito, deixe `b3` fora da ingestão (passo 3) ou não venda assinaturas.
Tesouro Direto (ODbL), PTAX e CDI são dados abertos.

## 1. Pré-requisitos do servidor

- [ ] Host Linux com Docker + Docker Compose e o CLI `sqlite3` (para backups). Nada é compilado no
      servidor: todos os serviços rodam a partir das imagens no GHCR.
- [ ] Domínio no Cloudflare, configurado conforme [`DEPLOY.md` §1](DEPLOY.md#1-cloudflare-dns-and-tls)
      (Full strict, Authenticated Origin Pulls, token de DNS).
- [ ] Imagens e arquivos de deploy conforme [`DEPLOY.md` §2](DEPLOY.md#2-get-the-images), Traefik conforme
      [§3](DEPLOY.md#3-traefik-trust-only-cloudflare), compose conforme
      [§4](DEPLOY.md#4-docker-composeyml). Ainda não rode `docker compose up`.
- [ ] Só a porta 443 (e SSH) acessível de fora. Nada escutando publicamente em 8080/8081/8082.
- [ ] Fixe no `.env` a versão publicada de cada imagem (`BRINDEX_*_VERSION`) e anote as anteriores:
      é o que você usa no rollback. Releases: [`DEPLOY.md` §8](DEPLOY.md#8-releasing).

## 2. Variáveis de ambiente

Cada serviço documenta suas variáveis no próprio `.env.example`; isto é o que produção precisa.

| Serviço | Onde | Variáveis |
|---|---|---|
| brindex-api | `environment:` no compose | `BRINDEX_DB_PATH=/data/brindex.sqlite`, `ACCOUNTS_DB_PATH=/data/accounts.sqlite`, `REQUIRE_API_KEY=true`, `RATE_LIMIT_PER_MINUTE` (padrão 60). Opcionais: `POINTS_MAX_ROWS`, `CORS_ALLOWED_ORIGINS`. Veja [`.env.example`](../.env.example). |
| brindex-admin | `brindex-admin.env` (modo `600`, nunca commitado) | `ADMIN_USER`, `ADMIN_PASSWORD` (≥ 16 caracteres), `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_ID`, `STRIPE_PORTAL_LOGIN_URL`, `STRIPE_LIVE_MODE` (passo 5). `ACCOUNTS_DB_PATH`, `PUBLIC_BASE_URL`, `ADMIN_HOST` ficam no compose. Veja o `.env.example` do brindex-admin. |
| brindex-ingest | `environment:` no compose | `BRINDEX_DB_PATH=/data/brindex.sqlite`. A fonte é um argumento de linha de comando (passo 3). |
| Compose | `.env` ao lado do compose ([`deploy/.env.example`](../deploy/.env.example)) | `BRINDEX_API_VERSION`, `BRINDEX_ADMIN_VERSION`, `BRINDEX_INGEST_VERSION`, `BRINDEX_UID`, `BRINDEX_GID`, `API_HOST`, `BILLING_HOST`, `ACME_EMAIL`, `CF_DNS_API_TOKEN` |

- [ ] `brindex-api` e `brindex-admin` apontam para o **mesmo** `accounts.sqlite`.
- [ ] `brindex-api` e `ingest` apontam para o **mesmo** `brindex.sqlite`.
- [ ] `./data` pertence a `BRINDEX_UID:BRINDEX_GID` (o usuário de deploy).
- [ ] Arquivos de ambiente com `chmod 600` e dono = usuário de deploy.

## 3. Ingestão: carga inicial e agendamento diário

A brindex-api não sobe enquanto o `brindex.sqlite` não tiver as tabelas, então carregue os dados
primeiro.

Rode a partir do diretório do compose (`/srv/brindex` no `DEPLOY.md`). O job `ingest` grava
`./data/brindex.sqlite` como `BRINDEX_UID` e termina; os argumentos depois de `ingest` vão para o
`brindex-ingest`. Rode sempre sob `flock ingest.lock`: duas execuções ao mesmo tempo (por exemplo,
um backfill manual e o cron) disputariam o lock do banco e uma delas falharia com
`database is locked`. Com `flock`, a segunda espera a primeira terminar.

```bash
cd /srv/brindex
# Carga inicial: histórico. A B3 baixa um arquivo de ~80 MB por ano.
flock ingest.lock docker compose run --rm ingest --since 2020-01-01 --since-year 2020
echo "exit code: $?"    # 0 só se todas as fontes funcionaram
```

`--source` aceita um valor só (`all`, o padrão, ou uma fonte). Para deixar `b3` de fora até resolver
o passo 0, rode as outras três:

```bash
for s in treasury-direct ptax cdi; do flock ingest.lock docker compose run --rm ingest --since 2020-01-01 --since-year 2020 --source $s; done
```

Cada fonte falha de forma independente; um exit diferente de zero lista no log as fontes que
falharam. Rodar de novo é seguro (upserts), então depois de uma queda passageira basta repetir.

Agendamento diário (cron do usuário de deploy, 22:00 no horário do servidor, depois que a B3 publica
o fechamento do dia):

```cron
0 22 * * * cd /srv/brindex && flock -w 7200 ingest.lock docker compose run --rm ingest >> /var/log/brindex-ingest.log 2>&1
```

(`-w 7200` espera até duas horas por uma execução manual e depois desiste com exit diferente de zero.
Sem `b3`: uma linha por fonte, cada uma terminando em `run --rm ingest --source <fonte>`.)

- [ ] Carga inicial terminou com exit code 0.
- [ ] Entrada do cron instalada; o usuário de deploy está no grupo `docker` e consegue gravar o log.
- [ ] Opcional: acrescente `&& curl -fsS https://hc-ping.com/<uuid>` (ou outro serviço de
      dead-man's switch) para ser avisado quando uma execução falhar ou não acontecer.

## 4. Subir os serviços com `REQUIRE_API_KEY=true`

1. Suba primeiro o brindex-admin, para ele criar o `accounts.sqlite` (a brindex-api não sobe sem ele
   quando `REQUIRE_API_KEY=true`):
   ```bash
   docker compose up -d traefik brindex-admin
   ```
2. Depois a brindex-api (o compose já tem `REQUIRE_API_KEY=true`):
   ```bash
   docker compose up -d brindex-api
   ```
3. Pelo túnel SSH ([`DEPLOY.md` §6](DEPLOY.md#6-using-the-admin-page)), crie em `/admin` uma chave
   cortesia para você. Ela é usada nos smoke tests e no monitoramento.

- [ ] Nunca rode produção com `REQUIRE_API_KEY=false`: as rotas de dados ficariam abertas a qualquer
      um.

## 5. Stripe: mudar para modo live

No Stripe, modo teste e modo live são mundos separados: produto, price, webhook, portal do cliente e
chaves precisam ser criados de novo no modo live. Os passos espelham o README do brindex-admin
("Stripe setup (test mode)"), em **modo live**:

- [ ] Conta ativada para pagamentos reais (dados da empresa e conta bancária no Dashboard).
- [ ] Products → o produto e o price mensal recorrente, criados no modo live → `STRIPE_PRICE_ID`.
- [ ] Developers → API keys → chave secreta live (`sk_live_...`) → `STRIPE_SECRET_KEY`. Prefira uma
      restricted key se tiver configurado uma, e guarde só no `brindex-admin.env`.
- [ ] Developers → Webhooks → endpoint live `https://billing.example.com/webhooks/stripe` com
      `customer.subscription.created`, `.updated`, `.deleted` → signing secret →
      `STRIPE_WEBHOOK_SECRET`. Se as entregas derem 403, veja [`DEPLOY.md` §5](DEPLOY.md#5-stripe-webhook-through-cloudflare).
- [ ] Settings → Billing → Customer portal, configurado no modo live → `STRIPE_PORTAL_LOGIN_URL`.
- [ ] `STRIPE_LIVE_MODE=true` no `brindex-admin.env` (sem isso o brindex-admin recusa chave live).
- [ ] `docker compose up -d --force-recreate brindex-admin`.
- [ ] **Rotacione a chave secreta de teste que foi colada no chat**: Dashboard em modo teste →
      Developers → API keys → *Roll key*. Role também o signing secret do webhook de teste se ele foi
      compartilhado. Atualize qualquer `.env` local que ainda use essas chaves.

## 6. Smoke tests

Rode de uma máquina fora do servidor. `$KEY` é a chave cortesia do passo 4 (mantenha fora do
histórico do shell: `read -s KEY`).

```bash
API=https://api.example.com

# Liveness e readiness (sem chave).
curl -s -o /dev/null -w '%{http_code}\n' $API/health    # 200
curl -s -o /dev/null -w '%{http_code}\n' $API/ready     # 200

# 401 sem chave, 200 com chave.
curl -s -o /dev/null -w '%{http_code}\n' $API/series                                   # 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $KEY" $API/series   # 200

# Dados em dia: a data deve ser o último dia útil.
curl -s -H "Authorization: Bearer $KEY" $API/v1/series/PTAX/USD/SELL/points/latest

# 429 do limite por chave: ~70 requisições em menos de um minuto, a 4/s para o limite por IP do
# Traefik (5/s) não responder antes. Espere 200s e depois 429 ao passar de RATE_LIMIT_PER_MINUTE.
for i in $(seq 1 70); do
  curl -s -o /dev/null -w '%{http_code} ' -H "Authorization: Bearer $KEY" $API/series; sleep 0.25
done; echo
```

Depois, o restante do [`DEPLOY.md` §7](DEPLOY.md#7-verify): o IP de origem recusa o handshake TLS,
a rajada por IP devolve 429 e `https://billing.example.com/admin` dá 404.

Compra ponta a ponta em modo live:

- [ ] Abra `https://billing.example.com/subscribe`, pague com cartão real, receba a chave (uma vez).
- [ ] Essa chave devolve 200 em `/series`; a conta aparece como `active` no `/admin`.
- [ ] Cancele pelo link do portal; quando o webhook chegar, a chave passa a devolver 401. Estorne a
      cobrança no Dashboard.

## 7. Backups

O `brindex.sqlite` pode ser reconstruído a partir das fontes (passo 3), devagar. O `accounts.sqlite`
**não**: ele guarda o hash da chave e o vínculo de assinatura de cada cliente. Faça backup dos dois,
diariamente, com o backup online do SQLite (seguro com os serviços rodando; nunca `cp` num SQLite em
uso):

```bash
#!/usr/bin/env bash
# /path/to/backup_brindex.sh
set -euo pipefail
DATA=/srv/brindex/data
OUT=/path/to/backups
DAY=$(date +%F)
sqlite3 "$DATA/accounts.sqlite" ".backup '$OUT/accounts-$DAY.sqlite'"
sqlite3 "$DATA/brindex.sqlite"  ".backup '$OUT/brindex-$DAY.sqlite'"
find "$OUT" -name '*.sqlite' -mtime +14 -delete    # guarda duas semanas localmente
```

```cron
30 23 * * * /path/to/backup_brindex.sh >> /var/log/brindex-backup.log 2>&1
```

- [ ] Backups copiados para fora do servidor (outro host ou object storage), criptografados se for
      terceiro: o `accounts.sqlite` tem e-mails de clientes e ids do Stripe.
- [ ] Restore testado uma vez: `sqlite3 accounts-<dia>.sqlite 'select count(*) from accounts'`.

## 8. Monitoramento

- [ ] Uptime check externo em `https://api.example.com/ready` (a cada 1–5 min). `/ready` devolve 503
      quando o banco não responde; `/health` só diz que o processo está de pé.
- [ ] Uptime check em `https://billing.example.com/health`.
- [ ] Alerta da ingestão do passo 3 (dead-man's switch ou leitura do log do cron).
- [ ] Stripe → Developers → Webhooks: ative os alertas por e-mail de entregas com falha.
- [ ] Alerta de espaço em disco no volume de dados (backfill do COTAHIST e backups crescem).
- [ ] Healthchecks: `docker compose ps` mostra brindex-api e brindex-admin como `healthy`.
- [ ] Logs: `docker compose logs -f brindex-api brindex-admin`. Mantenha os access logs do Traefik
      desligados ou com `RequestPath` descartado ([`DEPLOY.md` §4](DEPLOY.md#4-docker-composeyml)):
      o path pode trazer `?api_key=`.

## 9. Rollback

| Problema | Ação |
|---|---|
| Release ruim da brindex-api ou do brindex-admin | Volte a versão anterior no `.env` (`BRINDEX_API_VERSION` / `BRINDEX_ADMIN_VERSION`) e rode `docker compose up -d <serviço>`. |
| Ingestão ruim (valores errados) | Volte o `BRINDEX_INGEST_VERSION` anterior (ou publique uma correção) e rode `flock ingest.lock docker compose run --rm ingest` de novo no período afetado: os upserts sobrescrevem. Se não bastar, pare a brindex-api, restaure `brindex-<dia>.sqlite` sobre `data/brindex.sqlite` e suba de novo. |
| `accounts.sqlite` danificado | Pare brindex-admin e brindex-api, restaure o `accounts-<dia>.sqlite` mais recente e suba os dois. Assinaturas alteradas depois do backup são corrigidas no próximo webhook do Stripe daquele cliente; confira no Dashboard o que for mais novo que o backup. |
| Parar de vender (ex.: a B3 negar) | Tire `STRIPE_SECRET_KEY` do `brindex-admin.env` e recrie o brindex-admin: `/subscribe` e o webhook desligam, as chaves existentes continuam funcionando. Pause ou cancele as assinaturas no Dashboard. Para tirar os dados B3 da API, pare de ingerir `b3` (passo 3) e reconstrua o `brindex.sqlite` sem eles. |
| Chave do Stripe vazada | Role a chave no Dashboard, atualize o `brindex-admin.env`, recrie o brindex-admin. |

Nunca faça rollback colocando `REQUIRE_API_KEY=false`.
