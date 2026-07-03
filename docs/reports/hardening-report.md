# Hardening Report — Post-Implementation Review

*[Português](hardening-report.pt.md)*

This document details the full review carried out on the already-finished Card Process
implementation. Unlike [implementation-walkthrough.md](implementation-walkthrough.md) (which
narrates the original build), the focus here is **auditing already-shipped code**: what was wrong,
why it was wrong, and exactly what changed to fix it. The review was done in an isolated git
worktree (`worktree-hardening-review`), without touching the production code until full
validation.

Every issue below went through three steps: (1) mentally reproducing the failure scenario,
(2) a minimal, surgical fix, (3) validation with the test suite (`mvn test`, which went from 20 to
34 green tests).

---

## Table of contents

- [Critical bug 1 — Orphan card from a duplicate-CPF race](#critical-bug-1--orphan-card-from-a-duplicate-cpf-race)
- [Bug 2 — Formatted CPF slipped past uniqueness](#bug-2--formatted-cpf-slipped-past-uniqueness)
- [Bug 3 — Dead catch block in the Cartao consumer](#bug-3--dead-catch-block-in-the-cartao-consumer)
- [Improvement 1 — CPF leak in the error body (LGPD)](#improvement-1--cpf-leak-in-the-error-body-lgpd)
- [Improvement 2 — Missing resilience on the Portador→Cartao client](#improvement-2--missing-resilience-on-the-portadorcartao-client)
- [Improvement 3 — Incomplete exception handlers](#improvement-3--incomplete-exception-handlers)
- [Improvement 4 — CPF validator test coverage](#improvement-4--cpf-validator-test-coverage)
- [Improvement 5 — Restart policy in Docker Compose](#improvement-5--restart-policy-in-docker-compose)
- [Improvement 6 — OpenAPI contract out of sync with the real code](#improvement-6--openapi-contract-out-of-sync-with-the-real-code)
- [What was evaluated and consciously left out](#what-was-evaluated-and-consciously-left-out)
- [Validation result](#validation-result)

---

## Critical bug 1 — Orphan card from a duplicate-CPF race

**File:** `portador-service/src/main/java/com/cardprocess/portador/application/CardholderService.java`

### How the bug existed

The original code:

```java
@Transactional
public Cardholder register(String name, String cpf, LocalDate birthDate, UUID productId) {
    if (repository.existsByCpf(cpf)) {
        throw new DuplicateCpfException(cpf);
    }
    Cardholder cardholder = repository.save(Cardholder.register(name, cpf, birthDate, productId));
    UUID correlationId = UUID.randomUUID();
    issuancePublisher.publish(new IssuanceMessage(cardholder.getId(), productId, correlationId));
    ...
    return cardholder;
}
```

The critical detail is that `JpaRepository.save()` does **not** execute the `INSERT`
immediately. Hibernate only registers the entity as "pending persistence" in the
`PersistenceContext`; the physical `INSERT` only happens at *flush* time — which, by default,
happens at transaction commit, at the end of the method (Spring's `@Transactional` closes the
transaction after the method returns).

That opens a real race window:

1. Two concurrent HTTP requests arrive with the **same CPF** (e.g. a client that resends after a
   timeout, or two replicas of the same poorly-debounced form on the frontend).
2. Both pass `existsByCpf(cpf)` **before either commits** — the database's uniqueness constraint
   (`cardholder.cpf UNIQUE`) hasn't been violated yet because no `INSERT` has physically run yet.
   Both reads return `false`.
3. Both call `repository.save(...)` — which only queues the `INSERT` in memory, without touching
   the database.
4. **Both call `issuancePublisher.publish(...)`** and send an `IssuanceMessage` to the SQS queue,
   each with a `cardholderId` generated in memory (the UUID `@GeneratedValue` is already known
   before the flush).
5. Only at the end of the method, when the transaction tries to commit, does Hibernate finally
   execute the two `INSERT`s. One of them hits PostgreSQL's `UNIQUE(cpf)` constraint and rolls
   back.

**Result:** the losing request's `IssuanceMessage` is already on the queue, pointing at a
`cardholderId` that **never existed in the database** (its INSERT was never committed). The
Cartao Service consumes that message, confirms the product against the source of truth, and
**persists a card linked to a nonexistent cardholder** — exactly the "orphan card" scenario the
project's constitution explicitly forbids (Principle II: *Data Integrity over Availability for
Writes*). Worse: since the card has `cardholder_id UNIQUE`, that phantom card permanently
**blocks** issuing a legitimate card for that CPF, should it be successfully re-registered later.

The same pattern existed in `AuthService.register()`, though without the messaging side effect —
there the risk was just a success response (`201`) for a user who was actually rolled back.

### How it was fixed

```java
@Transactional
public Cardholder register(String name, String cpf, LocalDate birthDate, UUID productId) {
    String normalizedCpf = cpf.replaceAll("\\D", "");
    if (repository.existsByCpf(normalizedCpf)) {
        throw new DuplicateCpfException(normalizedCpf);
    }
    Cardholder cardholder = repository.saveAndFlush(
            Cardholder.register(name, normalizedCpf, birthDate, productId));
    UUID correlationId = UUID.randomUUID();
    issuancePublisher.publish(new IssuanceMessage(cardholder.getId(), productId, correlationId));
    ...
    return cardholder;
}
```

Swapping `save()` for `saveAndFlush()` forces the `INSERT` to run **immediately**, still inside the
transaction, but **before** the call to `issuancePublisher.publish(...)`. That changes the outcome
of the race: if two concurrent registrations arrive with the same CPF, the second one to reach
`flush()` gets the `DataIntegrityViolationException` **right then**, the method throws immediately,
and the call to the publisher **never happens** for the loser. The first one proceeds normally:
successful flush, then publish. There's no longer a window where a message is published for a
cardholder that's about to be rolled back.

The same fix was replicated in `AuthService.register()` (`saveAndFlush` on user registration),
closing the equivalent window there.

### Why the original tests didn't catch it

The existing integration tests (`CardholderIssuanceIntegrationTest`) only exercised the sequential
path — one request at a time. The race only manifests under real concurrency, which wasn't part of
the previous test scope. A new integration test
(`rejectsDuplicateCpfWithConflictAndMaskedDetail`, detailed in the next section) covers the
conflict path, though actually proving the race would require a dedicated concurrency test (two
threads racing the same insert) — left as a natural next step, since `saveAndFlush` already closes
the window deterministically and that's provable by code inspection.

---

## Bug 2 — Formatted CPF slipped past uniqueness

**File:** the same `CardholderService.java`, fix included in the same commit as Bug 1.

### How the bug existed

`CpfValidator` (used by the `@Cpf` annotation on input DTOs) accepts masked CPFs:

```java
String digits = value.replaceAll("\\D", "");
if (digits.length() != 11 || ...) { return false; }
```

That is, both `"39053344705"` and `"390.533.447-05"` pass validation — the validator extracts
only the digits to check the check digits. So far, correct.

The problem is that `CardholderService.register()` **didn't apply that same normalization**
before persisting. The string exactly as it came in the request JSON was written to the
`cpf VARCHAR(11)` column and used both in `existsByCpf(cpf)` and in
`Cardholder.register(name, cpf, ...)`. That created two simultaneous problems:

1. **The `UNIQUE(cpf)` constraint didn't catch duplicates with different formatting.** A
   registration with `"39053344705"` and another with `"390.533.447-05"` are the same CPF, but the
   database treats them as different strings — the uniqueness check (both `existsByCpf` and the
   SQL constraint) simply didn't detect the duplicate.
2. **Risk of silent truncation or INSERT failure.** The column is `VARCHAR(11)`; a masked input
   (`"390.533.447-05"`, 14 characters) exceeds that limit. Depending on the JDBC driver/Postgres
   configuration, that could produce a `value too long` error (a visible but confusing failure)
   instead of the expected `409 Conflict` for the "CPF already registered" business case.

### How it was fixed

Normalization (`cpf.replaceAll("\\D", "")`) now happens **exactly once**, at the top of the
`register()` method, and the normalized result is used both for the existence check and for
persistence (see the full diff in the previous section). A dedicated test proves the behavior:

```java
@Test
void registerNormalizesFormattedCpfBeforePersisting() {
    ...
    service.register("Maria Silva", "390.533.447-05", LocalDate.of(1990, 5, 12), UUID.randomUUID());

    ArgumentCaptor<Cardholder> captor = ArgumentCaptor.forClass(Cardholder.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getCpf()).isEqualTo("39053344705");
}
```

---

## Bug 3 — Dead catch block in the Cartao consumer

**File:** `cartao-service/src/main/java/com/cardprocess/cartao/application/CardIssuanceService.java`

### How the bug existed

```java
try {
    Card card = repository.save(Card.issue(...));
    log.info("Card issued ...");
} catch (DataIntegrityViolationException duplicate) {
    log.info("Concurrent duplicate issuance ignored correlationId={}", message.correlationId());
}
```

The intent was good: catch a constraint violation (`correlation_id UNIQUE` or
`cardholder_id UNIQUE`) caused by a concurrent SQS message redelivery and treat it as a silent
no-op, since idempotency is guaranteed by the constraint.

The problem is the same mechanism as Bug 1: `repository.save()` doesn't execute the `INSERT`
right away — it queues the operation for the next *flush*, which inside a Spring
`@Transactional` method normally only happens at commit, **after** the method has already
returned and the `try/catch` block has already closed. In other words, in practice this `catch`
was **never reached** by a constraint violation during Spring Data JPA + Hibernate's normal flow.
When the violation actually occurred (on a duplicate message redelivered exactly within the race
window), it surfaced as an `UnexpectedRollbackException` — or JPA's own `RollbackException` at
transaction commit, outside the scope of the method's `try/catch`, and therefore **not caught by
that specific catch**.

This didn't produce a visible functional bug right away (the message redelivered by SQS would
eventually be protected by `alreadyProcessed()` on a future redelivery, since that check *is*
performed before the save, at the start of the method), but it was **misleading dead code**: a
reader would incorrectly conclude that there's idempotency protection inside that try/catch block,
when in reality the only real, effective protection is the `alreadyProcessed()` check at the top
of the method, combined with SQS's *at-least-once* + redelivery nature.

### How it was fixed

The `try/catch` block was removed, leaving the code reflect the actual source of the idempotency
guarantee:

```java
ProductSnapshot product = productGateway.requireActiveProduct(message.productId());

Card card = repository.save(Card.issue(
        message.cardholderId(),
        message.productId(),
        cardNumberGenerator.generateMasked(),
        message.correlationId()));
log.info("Card issued cardId={} cardholderId={} productId={} product={}",
        card.getId(), card.getCardholderId(), product.id(), product.name());
```

Idempotency remains guaranteed by `alreadyProcessed()` (a check on `correlation_id` /
`cardholder_id` before any write attempt) plus the database's `UNIQUE` constraint as a final
safety net — but now without a block of code that suggests a protection that didn't actually
exist.

---

## Improvement 1 — CPF leak in the error body (LGPD)

**File:** `portador-service/src/main/java/com/cardprocess/portador/domain/DomainExceptions.java`

### What existed

```java
public static class DuplicateCpfException extends RuntimeException {
    public DuplicateCpfException(String cpf) {
        super("Cardholder already registered for CPF: " + cpf);
    }
}
```

This message feeds directly into the `detail` field of the `ProblemDetail` returned to the HTTP
client on a `409 Conflict` (via `GlobalExceptionHandler.handleConflict`). A CPF is personally
identifiable data under Brazil's LGPD (General Data Protection Law) — exposing the full number in
an HTTP error body is an unnecessary sensitive-data leak: the API client already knows the CPF it
sent, so echoing it back adds no functional value, only increases the exposure surface (access
logs, intermediate proxies, observability tooling that captures response bodies, etc. all end up
retaining the full CPF).

### How it was fixed

```java
public static class DuplicateCpfException extends RuntimeException {
    public DuplicateCpfException(String cpf) {
        super("Cardholder already registered for CPF: " + maskCpf(cpf));
    }

    private static String maskCpf(String cpf) {
        if (cpf == null || cpf.length() < 3) {
            return "***";
        }
        return "*********" + cpf.substring(cpf.length() - 2);
    }
}
```

The error message now exposes only the last two digits (`*********05`), enough for the user to
confirm which CPF collided without exposing the full value. An end-to-end integration test proves
the observable HTTP behavior:

```java
MvcResult conflict = mockMvc.perform(post("/cardholders")...)
        .andExpect(status().isConflict())
        .andReturn();
String detail = objectMapper.readTree(conflict.getResponse().getContentAsString()).get("detail").asText();
assertThat(detail).doesNotContain("52998224725");
```

---

## Improvement 2 — Missing resilience on the Portador→Cartao client

**File:** `portador-service/src/main/java/com/cardprocess/portador/infrastructure/client/CartaoClient.java`

### What existed

The project's constitution (Principle I — *Resilience First*) explicitly requires that "every
cross-service or infra call assumes the dependency can fail" and that synchronous integrations be
"wrapped with timeouts, retry with exponential backoff, and a circuit breaker." That rule had
already been correctly applied to the system's only other synchronous cross-service call —
`ProdutoServiceClient` (Cartao → Produto), which uses Resilience4j's `@CircuitBreaker` + `@Retry`.

Except there's a **second** synchronous cross-service call in the system: `CartaoClient`
(Portador → Cartao), used by `AggregationService` to assemble the response for
`GET /cardholders/{id}/aggregate`. That call only had connect/read timeouts
(`connectTimeout`/`readTimeout` of 2s/3s), with no retry and no circuit breaker:

```java
@Override
public Optional<CardView> findByCardholder(UUID cardholderId) {
    try {
        CartaoCardResponse response = restClient.get()...retrieve().body(...);
        return Optional.ofNullable(response).map(CartaoClient::toView);
    } catch (HttpClientErrorException.NotFound notFound) {
        return Optional.empty();
    } catch (RestClientException unavailable) {
        throw new CardServiceUnavailableException(unavailable);
    }
}
```

That meant a **transient** failure (a one-off network timeout, a quick restart of the Cartao
Service, a GC pause) already brought down the aggregate query on the first attempt, with no chance
of automatic recovery — inconsistent with the resilience pattern the project itself defines and
already applies elsewhere.

### How it was fixed

```java
private static final String RESILIENCE_INSTANCE = "cartaoService";

@Override
@CircuitBreaker(name = RESILIENCE_INSTANCE)
@Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "findByCardholderFallback")
public Optional<CardView> findByCardholder(UUID cardholderId) {
    ... // body unchanged
}

@SuppressWarnings("unused")
private Optional<CardView> findByCardholderFallback(UUID cardholderId, Throwable cause) {
    throw new CardServiceUnavailableException(cause);
}
```

Besides the `resilience4j-spring-boot3` dependency (already present at the root `pom.xml`, it just
needed declaring in the Portador module) and `spring-boot-starter-aop` (needed for the AOP proxies
behind Resilience4j's annotations to work), the `cartaoService` instance was configured in
`application.yml`, with the same parameters already used for `produtoService` in the Cartao
Service (3 attempts, exponential backoff starting at 200ms, a circuit breaker with a 10-call
window and a 50% failure threshold):

```yaml
resilience4j:
  retry:
    instances:
      cartaoService:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - com.cardprocess.portador.domain.DomainExceptions$CardServiceUnavailableException
  circuitbreaker:
    instances:
      cartaoService:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
```

Now **both** synchronous cross-service calls in the system (Cartao→Produto and
Portador→Cartao) follow exactly the same resilience pattern, closing the inconsistency.

---

## Improvement 3 — Incomplete exception handlers

**Files:** the three `GlobalExceptionHandler.java` classes (Produto, Portador, Cartao).

### What existed

Each `@RestControllerAdvice` only handled the known domain exceptions
(`ProductNotFoundException`, `CardholderNotFoundException`, etc.) and
`MethodArgumentNotValidException` (`@Valid` errors). There was no handler for the generic case —
**any unforeseen exception** (a `NullPointerException` from a future bug, an unexpected database
connection failure outside the already-handled paths, anything the explicit list of
`@ExceptionHandler`s didn't cover) fell through to Spring's default behavior: a `500` response
with the default whitelabel error body, potentially leaking stack-trace details depending on the
`server.error.include-*` configuration, and with no application-specific structured log pointing
at the problem.

That contradicted Principle I of the constitution — "a degraded dependency MUST surface as a
semantic HTTP status or a retryable message, never as an unhandled `500`" — and the project's own
README, which promises that "a global exception handler maps every error to a semantic HTTP
status ... never a raw stack trace." That promise only held for the explicitly listed exceptions.

Additionally, the Portador Service didn't handle `DataIntegrityViolationException` (which became
relevant after the Bug 1 fix) — without that handler, a uniqueness constraint violation not
explicitly caught in some service would result in `500` instead of the correct `409 Conflict`.

### How it was fixed

All three handlers now extend `ResponseEntityExceptionHandler` (Spring MVC's base class for
centralized exception handling, which already knows how to intercept framework-internal
exceptions like `MethodArgumentNotValidException` via *override* instead of a loose
`@ExceptionHandler`) and gained a catch-all handler:

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ... domain-specific handlers unchanged ...

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Validation failed");
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
```

In the Portador Service, a specific handler for `DataIntegrityViolationException` was also added
(`409 Conflict`, logged at `WARN` level), covering any uniqueness violation that escapes the
explicit `existsByCpf`/`existsByUsername` checks (for instance, under extreme concurrency, or in
future code paths that don't yet perform the upfront check).

With this change, the guarantee "every error becomes `application/problem+json`, never a stack
trace" became **structurally true** (any `Exception` falls into the catch-all) instead of
depending on every developer remembering to add an `@ExceptionHandler` for each new exception
class.

---

## Improvement 4 — CPF validator test coverage

**New file:** `portador-service/src/test/java/com/cardprocess/portador/web/validation/CpfValidatorTest.java`

### What was missing

`CpfValidator` (the CPF check-digit algorithm, used by the Bean Validation `@Cpf` annotation) had
no dedicated unit test. Its only indirect coverage came from integration tests that use
already-valid sample CPFs (`"39053344705"`) — meaning the algorithm itself (computing the two
check digits, rejecting repeated sequences like `"11111111111"`, rejecting the wrong length) was
never exercised against edge cases or invalid input.

That matters because it's a piece of business logic with multiple conditional paths (wrong
length, all-identical digits, wrong first check digit, wrong second check digit, null input) —
exactly the kind of code where a cheap unit test catches regressions that an integration test,
focused on the happy path, wouldn't.

### What was added

```java
class CpfValidatorTest {

    private final CpfValidator validator = new CpfValidator();

    @ParameterizedTest
    @ValueSource(strings = {"39053344705", "52998224725", "390.533.447-05"})
    void acceptsValidCpf(String cpf) {
        assertThat(validator.isValid(cpf, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "39053344704",   // wrong first check digit
            "39053344715",   // wrong second check digit
            "11111111111",   // all digits identical
            "00000000000",   // all digits identical
            "1234567890",    // too short
            "123456789012",  // too long
            "abcdefghijk",   // non-numeric
            ""                // empty
    })
    void rejectsInvalidCpf(String cpf) {
        assertThat(validator.isValid(cpf, null)).isFalse();
    }

    @Test
    void rejectsNullCpf() {
        assertThat(validator.isValid(null, null)).isFalse();
    }
}
```

12 parameterized cases covering: a plain valid CPF, a masked valid CPF, each of the two check
digits being wrong in isolation, repeated sequences, wrong lengths (short and long), non-numeric
input, an empty string and `null`.

---

## Improvement 5 — Restart policy in Docker Compose

**File:** `docker-compose.yml`

### What existed

None of the 6 services (`postgres`, `redis`, `localstack`, `produto-service`, `portador-service`,
`cartao-service`) declared a `restart` policy. Docker's default behavior in that case is `no` — if
a container goes down (a JVM crash, the OOM killer, an unhandled exception during bean
initialization, a momentary network glitch that drops the driver connection and kills the
process), it **stays down indefinitely** until manual intervention (`docker compose up` again).

That directly conflicts with the project's own framing — the README describes Portador/Cartao as
part of a "financial-sector platform... that must never leave a cardholder without a response" —
and is an obvious operational gap: an ecosystem designed to tolerate *external dependency*
failures (Redis, Produto Service, SQS) did nothing to recover from a failure of the **process
itself**.

### How it was fixed

`restart: unless-stopped` was added to each of the 6 services in `docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: cardprocess-postgres
    restart: unless-stopped
    ...
  redis:
    ...
    restart: unless-stopped
    ...
  # (same line added to localstack, produto-service, portador-service, cartao-service)
```

`unless-stopped` was chosen (over `always`) because it respects an operator's explicit manual stop
(`docker compose stop <service>`) — which is exactly the mechanism used by the README's own
resilience demo scenarios (`docker compose stop redis`, `docker compose stop produto-service`) to
simulate controlled unavailability. With `always`, those demo commands would have the container
automatically restarted by Docker before the operator could observe the degraded behavior — the
opposite of what's meant to be demonstrated.

---

## Improvement 6 — OpenAPI contract out of sync with the real code

**File:** `specs/001-card-processing-ecosystem/contracts/produto-openapi.yaml`

### What existed

The Produto Service's OpenAPI contract (a spec-kit design artifact, part of the contract
documentation generated during the planning Phase 1) described the cancel operation as:

```yaml
/products/{id}:
  patch:
    summary: Cancel a product
    operationId: cancelProduct
```

That is, a `PATCH` on the same path used by `GET` (`/products/{id}`). But the actually
implemented controller uses a dedicated sub-resource:

```java
@PatchMapping("/{id}/cancel")
public ProductResponse cancel(@PathVariable UUID id) { ... }
```

In other words, the real path is `PATCH /products/{id}/cancel`, not `PATCH /products/{id}`. This
mismatch is the kind of documentation drift that naturally arises during implementation (a design
decision changed after the contract was written) but that, left unfixed, undermines the
reliability of contract artifacts as a source of truth — someone integrating off the YAML would
try `PATCH /products/{id}` and get a `405 Method Not Allowed` or unexpected behavior, since that
path only accepts `GET` on the real controller.

### How it was fixed

The contract was adjusted to reflect the real path, moving the `PATCH` operation to its own
`/products/{id}/cancel` entry:

```yaml
/products/{id}/cancel:
  patch:
    summary: Cancel a product
    operationId: cancelProduct
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: string
          format: uuid
    responses:
      '200':
        description: Product cancelled
        ...
```

The README was also updated (the API reference table and the resilience matrix) to incorporate
the new guarantees described in the previous sections (retry/circuit breaker on the
Portador→Cartao client, write-race prevention, CPF masking, `restart: unless-stopped`).

---

## What was evaluated and consciously left out

Not every improvement identified during the review was implemented — some were evaluated and
dropped either because they fell outside the proposed scope, or because they'd require a larger
architectural change than the targeted hardening proposed here:

- **Transactional outbox in the Portador Service.** `saveAndFlush` (Bug 1) closes the race window
  between concurrent requests, but doesn't eliminate a smaller residual window: if the cardholder
  `INSERT` succeeds and the transaction commits, but the process crashes (JVM crash, network
  failure to SQS) *between* the commit and the call to `issuancePublisher.publish(...)`, the
  issuance message is never sent and the cardholder stays registered without a card, permanently,
  with no automatic recovery mechanism. The definitive solution for this class of problem is the
  *Transactional Outbox* pattern (the message is written in the same transaction as the
  cardholder, into an outbox table, and a separate asynchronous process — or a *Change Data
  Capture* tool like Debezium — guarantees actual delivery to the queue). This was already
  documented as a natural evolution in the original README and remains so here: it's a messaging
  infrastructure change, not a point bug, and is out of scope for this hardening review. *(Note:
  this gap was subsequently closed — see [implementation-walkthrough.md § 11](implementation-walkthrough.md#11-post-review-hardening-transactional-outbox)
  for the transactional outbox that was later implemented.)*
- **Authentication on the Produto and Cartao services.** Both remain without JWT, by design — the
  scope specifies authentication only on the Portador Service (the only entry point facing human
  operators); Produto and Cartao are only called internally, by other services in the mesh.
- **Advanced observability (distributed tracing, custom metrics).** Mentioned in the README as a
  natural evolution (Micrometer + tracing), but not implemented in this review — it wasn't
  identified as a bug or a critical gap, just a longer-term operational-maturity gain.
- **A dedicated concurrency test for Bug 1.** The fix (`saveAndFlush`) is provable by code
  inspection (the flush forces the INSERT before the publish, closing the window by construction),
  but a test that fires two genuinely concurrent requests (via an `ExecutorService` with two real
  threads hitting the same endpoint) would provide additional empirical proof. Left out of this
  round because it's a more expensive infrastructure test (flaky by nature if done poorly) and not
  essential to validate the fix, since the root cause (flush execution order) is deterministic,
  not probabilistic.

---

## Validation result

After all the fixes, the full test suite (`mvn test`, unit + integration via Testcontainers with
real Postgres, Redis and LocalStack) was run from scratch:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0   -- produto-service
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  -- portador-service
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  -- cartao-service
[INFO] BUILD SUCCESS
```

**34 green tests** (up from 20 before this review) — the increase of 14 tests comes entirely from
the new coverage: 12 cases in `CpfValidatorTest`, 1 test for formatted-CPF normalization, and 1
end-to-end integration test for the duplicate-CPF conflict flow with masking verification in the
HTTP response body.

All changes were committed in 8 atomic commits on the `worktree-hardening-review` branch, grouped
logically by problem/improvement, ready for review and merge into `main`.
