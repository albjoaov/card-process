# Deploy em produção: VPS + Coolify

Runbook para colocar o ecossistema em produção na opção mais barata e simples: um único VPS
gerenciado pelo [Coolify](https://coolify.io), usando [`docker-compose.prod.yml`](../../docker-compose.prod.yml).
Custo total de referência: **~€6–10/mês** (VPS de 4 vCPU / 8 GB, ex.: Hetzner CX32/CPX31,
DigitalOcean, Contabo) + domínio.

## Decisões e trade-offs

### Por que Coolify (e não Dokploy ou compose cru)

- **Compose cru** não dá TLS, deploy por git push, UI de segredos nem rollback.
- **Dokploy** é uma boa alternativa, mas o Coolify é mais maduro, tem comunidade maior e
  suporte de primeira classe a recursos do tipo *Docker Compose* — que é exatamente o formato
  deste repositório.
- O Coolify embute **Traefik + Let's Encrypt** (TLS automático), **webhooks de auto-deploy**,
  gestão de variáveis/segredos e notificações (e-mail/Discord/Telegram) por healthcheck.

### Por que ElasticMQ no lugar do LocalStack

O LocalStack é uma ferramenta de *desenvolvimento*; em produção ele é pesado (Python, centenas de MB)
e a versão free não persiste estado. O [ElasticMQ](https://github.com/softwaremill/elasticmq)
fala o **mesmo protocolo SQS** — nenhuma linha de código Java muda, apenas o endpoint — e:

- As filas são **declarativas** em [`infra/elasticmq/elasticmq.conf`](../../infra/elasticmq/elasticmq.conf),
  espelhando o `init-sqs.sh` do dev: `card-issuance-queue` com redrive para `card-issuance-dlq`
  após `maxReceiveCount=5` (semântica verificada: a mensagem move para a DLQ na 6ª tentativa de
  recebimento, igual ao SQS real).
- **Mensagens persistem em H2** num volume (`messages-storage`): mensagens na fila e na DLQ
  sobrevivem a restart do container (verificado).
- Imagem nativa (GraalVM): ~60 MB, arranque em ~50 ms, ~256 MB de RAM.

**Trade-off honesto**: ElasticMQ é um broker single-node sem replicação. Para este volume
(emissão de cartões, fila quase sempre vazia) é adequado; se o produto crescer, a migração para
**SQS real** é trocar 3 variáveis de ambiente (`SPRING_CLOUD_AWS_SQS_ENDPOINT` removida +
credenciais IAM) — nenhum código muda. Esse é o benefício de manter o contrato SQS.

### Topologia de rede: só o Portador é público

Somente o **portador-service** recebe domínio público (é a única API autenticada por JWT).
`produto-service` e `cartao-service` ficam **apenas na rede interna** do compose — isso mitiga,
na camada de rede, o fato de os endpoints internos (ex.: `PATCH /cards/{id}/status`) não terem
autenticação própria. Postgres, Redis e ElasticMQ não publicam porta alguma no host.

Operações administrativas (criar/cancelar produto, mudar status de cartão) são feitas por
**túnel SSH** (abaixo) ou, se preferir expor com proteção, adicionando um domínio + middleware de
Basic Auth do Traefik pela UI do Coolify.

## Passo a passo

### 1. Provisionar o VPS

- 4 vCPU / 8 GB / 40 GB+ (o build das 3 imagens Maven acontece no próprio servidor; 4 GB fica
  apertado durante builds concorrentes).
- Ubuntu 24.04 LTS, acesso SSH por chave (desabilite senha em `/etc/ssh/sshd_config`).
- Firewall (do provedor ou `ufw`): libere **22, 80, 443** e, temporariamente, **8000**
  (UI do Coolify até você atribuir um domínio a ela).

### 2. Instalar o Coolify

```bash
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | bash
```

Acesse `http://IP_DO_VPS:8000`, crie o usuário admin, e em *Settings* atribua um domínio à
própria UI (ex.: `coolify.seudominio.com`) para tê-la atrás de TLS — depois feche a porta 8000
no firewall.

### 3. Criar o recurso Docker Compose

1. **Projects → New Project** → adicione um recurso do tipo **Docker Compose** (public/private
   repository, conforme o repo).
2. Aponte para o repositório e branch de produção, e em **Compose file** informe
   `docker-compose.prod.yml`.
3. Em **Environment Variables**, defina (marcando como *secret*):
   - `POSTGRES_PASSWORD` → `openssl rand -base64 24`
   - `JWT_SECRET` → `openssl rand -base64 48`

   O compose **falha rápido** se qualquer uma faltar (sintaxe `${VAR:?}`), então não existe o
   risco do segredo default `change-me` ir a produção.
4. No serviço **portador-service**, configure o domínio público (ex.:
   `https://api.seudominio.com`) apontando para a porta **8082**. Não configure domínio para os
   demais serviços. O DNS do domínio deve ter um registro `A` para o IP do VPS.
5. **Deploy**. O Coolify clona o repo, faz o build das três imagens no servidor (primeiro build:
   ~5–10 min) e sobe a stack. O Traefik emite o certificado Let's Encrypt automaticamente.
6. Em **Webhooks**, habilite o auto-deploy no push da branch de produção (GitHub webhook).

### 4. Smoke test

```bash
# Saúde pública
curl -s https://api.seudominio.com/actuator/health

# Criar produto (interno -> via túnel SSH)
ssh -N -L 8081:localhost:8081 usuario@IP_DO_VPS &   # se necessário: docker port / IP do container
```

Para operar os serviços internos sem expor portas, o caminho mais simples é executar de dentro da
rede do compose no próprio VPS:

```bash
docker compose -f docker-compose.prod.yml exec portador-service \
  curl -s http://produto-service:8081/products -H 'Content-Type: application/json' \
  -d '{"name":"Black"}'
```

Depois siga o fluxo do [README](../../README.md#end-to-end-demo) (register → login → cardholder →
aggregate) contra `https://api.seudominio.com`.

## Operação

### Inspecionar a fila e a DLQ

```bash
docker run --rm --network card-process-prod_default \
  -e AWS_ACCESS_KEY_ID=elasticmq -e AWS_SECRET_ACCESS_KEY=elasticmq -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli sqs get-queue-attributes \
  --endpoint-url http://elasticmq:9324 \
  --queue-url http://elasticmq:9324/000000000000/card-issuance-dlq \
  --attribute-names ApproximateNumberOfMessages
```

**DLQ > 0 é incidente** (cartão não emitido): investigue o produto referido na mensagem e, após
corrigir a causa, reenvie a mensagem da DLQ para a fila principal (receive + send + delete).

### Backups

O sidecar `db-backup` roda `pg_dump` diário dos três bancos com retenção de 7 dias no volume
`db-backups`. Restaurar:

```bash
docker compose -f docker-compose.prod.yml exec db-backup ls /backups
docker compose -f docker-compose.prod.yml exec -T postgres \
  sh -c 'gunzip -c /caminho/backup.sql.gz | psql -U cardprocess -d portador_db'
```

**Backup local não é DR**: copie o volume para fora do VPS (rclone para um object storage
S3-compatível — Backblaze B2/Cloudflare R2 custam centavos — ou os snapshots do provedor).

### Métricas (Prometheus)

Cada serviço expõe `GET /actuator/prometheus` numa **porta de management interna (9090)**, separada
da porta da aplicação — como só a porta 8082 do Portador é proxiada publicamente, as métricas nunca
ficam acessíveis pela internet (importante: o `SecurityConfig` do Portador faz `permitAll` em
`/actuator/**`, então mantê-las na porta pública as vazaria). Os healthchecks do compose também
usam a porta 9090.

Estão inclusas as métricas RED de HTTP, JVM, HikariCP, cache e **Resilience4j** (estado dos dois
circuit breakers: `resilience4j_circuitbreaker_state`). Para coletar:

- **Mais simples**: [Grafana Cloud free tier](https://grafana.com/products/cloud/) + Grafana Alloy
  no VPS fazendo scrape de `http://<container>:9090/actuator/prometheus` e `remote_write` — sem
  hospedar Prometheus.
- **Self-hosted**: suba Prometheus + Grafana como outro recurso Compose no Coolify, na mesma rede
  Docker da stack.

Alertas mínimos: profundidade da DLQ > 0 (incidente), circuit breaker aberto, e p99 do
`http_server_requests` no aggregate.

### Deploy, rollback e observação

- **Deploy**: push na branch → webhook → Coolify rebuilda e recria os containers (haverá alguns
  segundos de indisponibilidade por serviço; aceitável para single-node — o `restart:
  unless-stopped` e os healthchecks cuidam do resto).
- **Rollback**: `Deployments` → redeploy de um build anterior pela UI do Coolify (ou reverta o
  commit e push).
- **Alertas**: configure *Notifications* no Coolify (healthcheck falhou, deploy falhou) e um
  uptime externo gratuito (UptimeRobot/Better Stack) contra `/actuator/health` do domínio público.

## Limitações conhecidas (e o caminho de evolução)

| Limitação | Mitigação atual | Evolução |
|---|---|---|
| Single-node: manutenção do VPS = downtime | Healthchecks + restart + deploy rápido | 2º nó + LB, ou migrar p/ ECS/Cloud Run |
| ElasticMQ sem replicação | Persistência H2 em volume + DLQ | SQS real (troca de env vars) |
| Sem tracing distribuído | Métricas Prometheus internas (porta 9090) + logs | OpenTelemetry propagando o `correlationId` |
| Builds no próprio VPS | 8 GB de RAM absorve | CI (GitHub Actions) publicando em GHCR |
