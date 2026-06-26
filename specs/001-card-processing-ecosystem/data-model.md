# Phase 1 Design: Data Model

Database-per-service. Every persisted entity carries audit timestamps via JPA auditing.

## Produto Service - `produto_db`

### Product

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK |
| name | varchar(120) | not null |
| status | varchar(20) | not null, enum `ProductStatus` |
| created_at | timestamptz | not null, set on insert |
| updated_at | timestamptz | not null, set on update |

`ProductStatus`: `ATIVO`, `CANCELADO`.

**Rules**: created as `ATIVO`. Cancelling sets `CANCELADO` (existing cards unaffected). Fetch by id
returns `404` when absent.

## Portador Service - `portador_db`

### AppUser (authentication)

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK |
| username | varchar(80) | not null, unique |
| password_hash | varchar(100) | not null (BCrypt) |
| created_at | timestamptz | not null |

### Cardholder

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK |
| name | varchar(120) | not null |
| cpf | varchar(11) | not null, unique, validated |
| birth_date | date | not null, past |
| status | varchar(20) | not null, enum `CardholderStatus` |
| product_id | UUID | not null (reference to Produto product) |
| created_at | timestamptz | not null |
| updated_at | timestamptz | not null |

`CardholderStatus`: `ATIVO`, `BLOQUEADO`, `CANCELADO`.

**Rules**: created as `ATIVO`. Duplicate CPF -> `409`. On successful create, an `IssuanceMessage`
is published to SQS. CPF validated for length/check-digits; `birth_date` must be in the past.

## Cartao Service - `cartao_db`

### Card

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK |
| cardholder_id | UUID | not null, unique |
| product_id | UUID | not null |
| masked_number | varchar(19) | not null (e.g. `**** **** **** 1234`) |
| status | varchar(20) | not null, enum `CardStatus` |
| correlation_id | UUID | not null, unique (idempotency key) |
| created_at | timestamptz | not null |
| updated_at | timestamptz | not null |

`CardStatus`: `ATIVO`, `BLOQUEADO`, `CANCELADO`.

**Rules**: created as `ATIVO` only after the product is confirmed `ATIVO` at the source of truth.
Unique `correlation_id` and unique `cardholder_id` enforce idempotency against redelivery.

## Cross-service contract

### IssuanceMessage (SQS payload, `shared-contracts`)

| Field | Type | Notes |
|-------|------|-------|
| cardholderId | UUID | who the card is for |
| productId | UUID | which product to validate and embed |
| correlationId | UUID | idempotency + tracing key |

### ProductSnapshot (Redis value + aggregated response projection)

| Field | Type |
|-------|------|
| id | UUID |
| name | string |
| status | string |

Redis key: `product:{id}`, TTL default 10m.

## State machines

```text
Product:    (new) --> ATIVO --> CANCELADO
Cardholder: (new) --> ATIVO --> BLOQUEADO <--> ATIVO ; any --> CANCELADO
Card:       (issuance confirmed) --> ATIVO --> BLOQUEADO <--> ATIVO ; any --> CANCELADO
```

## Integrity guarantees (summary)

1. **No orphan cards**: write authorization confirms product `ATIVO` against Produto Service origin
   before insert; failure -> no insert -> SQS redrive -> DLQ.
2. **No duplicate cards**: unique `correlation_id` and `cardholder_id`; consumer is idempotent.
3. **Auditability**: every table records `created_at`/`updated_at` automatically.
