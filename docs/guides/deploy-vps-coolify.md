# Production Deployment: VPS + Coolify

*[Português](deploy-vps-coolify.pt.md)*

Runbook for putting the ecosystem into production the cheapest, simplest way: a single VPS
managed by [Coolify](https://coolify.io), using [`docker-compose.prod.yml`](../../docker-compose.prod.yml).
Reference total cost: **~€6–10/month** (4 vCPU / 8 GB VPS, e.g. Hetzner CX32/CPX31,
DigitalOcean, Contabo) + domain.

## Decisions and trade-offs

### Why Coolify (and not Dokploy or raw compose)

- **Raw compose** gives you no TLS, no git-push deploys, no secrets UI, no rollback.
- **Dokploy** is a decent alternative, but Coolify is more mature, has a larger community and
  first-class support for *Docker Compose*-type resources — exactly this repository's format.
- Coolify bundles **Traefik + Let's Encrypt** (automatic TLS), **auto-deploy webhooks**,
  variable/secret management and healthcheck notifications (email/Discord/Telegram).

### Why ElasticMQ instead of LocalStack

LocalStack is a *development* tool; in production it's heavy (Python, hundreds of MB) and the
free tier doesn't persist state. [ElasticMQ](https://github.com/softwaremill/elasticmq)
speaks the **same SQS protocol** — not a single line of Java code changes, only the endpoint — and:

- Queues are **declarative** in [`infra/elasticmq/elasticmq.conf`](../../infra/elasticmq/elasticmq.conf),
  mirroring dev's `init-sqs.sh`: `card-issuance-queue` with a redrive policy to `card-issuance-dlq`
  after `maxReceiveCount=5` (verified semantics: the message moves to the DLQ on the 6th receive
  attempt, same as real SQS).
- **Messages persist in H2** on a volume (`messages-storage`): messages in the queue and the DLQ
  survive a container restart (verified).
- Native image (GraalVM): ~60 MB, ~50 ms startup, ~256 MB RAM.

**Honest trade-off**: ElasticMQ is a single-node broker with no replication. For this volume
(card issuance, queue almost always empty) that's adequate; if the product grows, migrating to
**real SQS** is a 3-environment-variable swap (`SPRING_CLOUD_AWS_SQS_ENDPOINT` removed +
IAM credentials) — no code changes. That's the benefit of keeping the SQS contract.

### Network topology: only Portador is public

Only **portador-service** gets a public domain (it's the only JWT-authenticated API).
`produto-service` and `cartao-service` stay **internal-network-only** in the compose file — this
mitigates, at the network layer, the fact that internal endpoints (e.g. `PATCH /cards/{id}/status`)
have no authentication of their own. Postgres, Redis and ElasticMQ publish no port on the host.

Administrative operations (create/cancel product, change card status) are done via
**SSH tunnel** (below), or, if you'd rather expose them with protection, by adding a domain +
Traefik Basic Auth middleware through the Coolify UI.

## Step by step

### 1. Provision the VPS

- 4 vCPU / 8 GB / 40 GB+ (the 3 Maven images are built on the server itself; 4 GB gets tight
  during concurrent builds).
- Ubuntu 24.04 LTS, key-based SSH access (disable password auth in `/etc/ssh/sshd_config`).
- Firewall (provider's or `ufw`): open **22, 80, 443** and, temporarily, **8000**
  (Coolify's UI until you assign it a domain).

### 2. Install Coolify

```bash
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | bash
```

Go to `http://VPS_IP:8000`, create the admin user, and under *Settings* assign a domain to
the UI itself (e.g. `coolify.yourdomain.com`) so it sits behind TLS — then close port 8000
in the firewall.

### 3. Create the Docker Compose resource

1. **Projects → New Project** → add a **Docker Compose** resource (public/private
   repository, depending on the repo).
2. Point it at the production repository and branch, and under **Compose file** enter
   `docker-compose.prod.yml`.
3. Under **Environment Variables**, set (marking them as *secret*):
   - `POSTGRES_PASSWORD` → `openssl rand -base64 24`
   - `JWT_SECRET` → `openssl rand -base64 48`

   The compose file **fails fast** if either is missing (`${VAR:?}` syntax), so there's no
   risk of the `change-me` default secret reaching production.
4. On the **portador-service** service, configure the public domain (e.g.
   `https://api.yourdomain.com`) pointing at port **8082**. Don't configure a domain for the
   other services. The domain's DNS needs an `A` record pointing at the VPS IP.
5. **Deploy**. Coolify clones the repo, builds the three images on the server (first build:
   ~5–10 min) and brings up the stack. Traefik issues the Let's Encrypt certificate automatically.
6. Under **Webhooks**, enable auto-deploy on push to the production branch (GitHub webhook).

### 4. Smoke test

```bash
# Public health check
curl -s https://api.yourdomain.com/actuator/health

# Create a product (internal -> via SSH tunnel)
ssh -N -L 8081:localhost:8081 user@VPS_IP &   # if needed: docker port / container IP
```

To operate the internal services without exposing ports, the simplest path is to run from
inside the compose network on the VPS itself:

```bash
docker compose -f docker-compose.prod.yml exec portador-service \
  curl -s http://produto-service:8081/products -H 'Content-Type: application/json' \
  -d '{"name":"Black"}'
```

Then follow the flow from the [README](../../README.md#end-to-end-demo) (register → login → cardholder →
aggregate) against `https://api.yourdomain.com`.

## Operations

### Inspect the queue and the DLQ

```bash
docker run --rm --network card-process-prod_default \
  -e AWS_ACCESS_KEY_ID=elasticmq -e AWS_SECRET_ACCESS_KEY=elasticmq -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli sqs get-queue-attributes \
  --endpoint-url http://elasticmq:9324 \
  --queue-url http://elasticmq:9324/000000000000/card-issuance-dlq \
  --attribute-names ApproximateNumberOfMessages
```

**DLQ > 0 is an incident** (card not issued): investigate the product referenced in the message
and, once the root cause is fixed, redrive the message from the DLQ back to the main queue
(receive + send + delete).

### Backups

The `db-backup` sidecar runs a daily `pg_dump` of all three databases with 7-day retention on the
`db-backups` volume. Restoring:

```bash
docker compose -f docker-compose.prod.yml exec db-backup ls /backups
docker compose -f docker-compose.prod.yml exec -T postgres \
  sh -c 'gunzip -c /path/to/backup.sql.gz | psql -U cardprocess -d portador_db'
```

**A local backup is not DR**: copy the volume off the VPS (rclone to an S3-compatible object
store — Backblaze B2/Cloudflare R2 cost cents — or the provider's snapshots).

### Metrics (Prometheus)

Each service exposes `GET /actuator/prometheus` on a **separate internal management port (9090)**,
distinct from the application port — since only Portador's port 8082 is proxied publicly, metrics
are never reachable from the internet (important: Portador's `SecurityConfig` sets `permitAll` on
`/actuator/**`, so keeping it on the public port would leak them). The compose healthchecks also
use port 9090.

RED metrics for HTTP, JVM, HikariCP, cache and **Resilience4j** (state of both circuit breakers:
`resilience4j_circuitbreaker_state`) are all included. To collect them:

- **Simplest**: [Grafana Cloud free tier](https://grafana.com/products/cloud/) + Grafana Alloy
  on the VPS scraping `http://<container>:9090/actuator/prometheus` and `remote_write`-ing it —
  no self-hosted Prometheus needed.
- **Self-hosted**: bring up Prometheus + Grafana as another Compose resource in Coolify, on the
  same Docker network as the stack.

Minimum alerts: DLQ depth > 0 (incident), an open circuit breaker, and p99 of
`http_server_requests` on the aggregate endpoint.

### Deploy, rollback and observability

- **Deploy**: push to the branch → webhook → Coolify rebuilds and recreates the containers (a few
  seconds of downtime per service; acceptable for a single node — `restart:
  unless-stopped` and the healthchecks handle the rest).
- **Rollback**: `Deployments` → redeploy a previous build from the Coolify UI (or revert the
  commit and push).
- **Alerts**: configure *Notifications* in Coolify (healthcheck failed, deploy failed) and a free
  external uptime monitor (UptimeRobot/Better Stack) against the public domain's `/actuator/health`.

## Known limitations (and the evolution path)

| Limitation | Current mitigation | Evolution |
|---|---|---|
| Single-node: VPS maintenance = downtime | Healthchecks + restart + fast deploys | 2nd node + LB, or migrate to ECS/Cloud Run |
| ElasticMQ has no replication | H2 persistence on a volume + DLQ | Real SQS (env var swap) |
| No distributed tracing | Internal Prometheus metrics (port 9090) + logs | OpenTelemetry propagating `correlationId` |
| Builds run on the VPS itself | 8 GB RAM absorbs it | CI (GitHub Actions) publishing to GHCR |
