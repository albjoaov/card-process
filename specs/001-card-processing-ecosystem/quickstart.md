# Phase 1 Design: Quickstart

## Prerequisites

- Docker + Docker Compose (only hard requirement to run the ecosystem).
- Optional for local dev/tests: Java 21 and Maven 3.9+.

## Boot the whole ecosystem (single command)

```bash
docker compose up --build
```

This builds and starts: PostgreSQL, Redis, LocalStack (SQS), and the three services. Health checks
gate startup ordering. Ready when all containers are healthy.

Service URLs:

| Service | Base URL | Swagger UI |
|---------|----------|------------|
| Produto | http://localhost:8081 | http://localhost:8081/swagger-ui.html |
| Portador | http://localhost:8082 | http://localhost:8082/swagger-ui.html |
| Cartao | http://localhost:8083 | http://localhost:8083/swagger-ui.html |

## End-to-end demo

```bash
# 1. Create a product (Produto Service)
PRODUCT_ID=$(curl -s -X POST http://localhost:8081/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Black"}' | jq -r .id)

# 2. Register an app user and log in (Portador Service)
curl -s -X POST http://localhost:8082/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","password":"secret123"}'

TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","password":"secret123"}' | jq -r .token)

# 3. Register a cardholder -> enqueues issuance to SQS
CARDHOLDER_ID=$(curl -s -X POST http://localhost:8082/cardholders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Maria Silva\",\"cpf\":\"39053344705\",\"birthDate\":\"1990-05-12\",\"productId\":\"$PRODUCT_ID\"}" | jq -r .id)

# 4. Poll the aggregated view until the card is issued (eventual consistency)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/cardholders/$CARDHOLDER_ID/aggregate | jq
```

Expected final aggregate: cardholder `ATIVO` + card `ATIVO` (masked number) + product `Black`.

## Resilience demos

```bash
# Redis down -> product reads still succeed via direct Produto calls
docker compose stop redis
curl -s http://localhost:8083/cards/by-cardholder/$CARDHOLDER_ID | jq   # still works
docker compose start redis

# Produto Service offline -> no orphan card, message redrives to DLQ
docker compose stop produto-service
# register another cardholder; after maxReceiveCount the message lands in card-issuance-dlq
# inspect DLQ depth:
docker compose exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/card-issuance-dlq \
  --attribute-names ApproximateNumberOfMessages
docker compose start produto-service
```

## Run the tests

```bash
mvn -q -T1C test        # unit + Testcontainers integration tests for all modules
```

Testcontainers spins up ephemeral Postgres, Redis and LocalStack for the integration tests, so Docker
must be running. No manual infra setup is needed.
