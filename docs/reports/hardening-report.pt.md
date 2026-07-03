# Relatório de Hardening — Revisão Pós-Implementação

*[English](hardening-report.md)*

Este documento detalha a revisão completa realizada sobre a implementação já finalizada do Card Process. Diferente do
[implementation-walkthrough.pt.md](implementation-walkthrough.pt.md) (que narra a construção original), aqui
o foco é **auditoria de código já pronto**: o que estava errado, por que estava errado, e exatamente o
que mudou para corrigir. A revisão foi feita num git worktree isolado
(`worktree-hardening-review`), sem tocar o código em produção até a validação completa.

Todos os problemas abaixo passaram por três etapas: (1) reprodução mental do cenário de falha,
(2) correção mínima e cirúrgica, (3) validação com a suíte de testes (`mvn test`, que foi de 20 para
34 testes verdes).

---

## Índice

- [Bug crítico 1 — Cartão órfão por corrida de CPF duplicado](#bug-crítico-1--cartão-órfão-por-corrida-de-cpf-duplicado)
- [Bug 2 — CPF formatado furava a unicidade](#bug-2--cpf-formatado-furava-a-unicidade)
- [Bug 3 — Catch morto no consumidor do Cartão](#bug-3--catch-morto-no-consumidor-do-cartão)
- [Melhoria 1 — Vazamento de CPF no corpo do erro (LGPD)](#melhoria-1--vazamento-de-cpf-no-corpo-do-erro-lgpd)
- [Melhoria 2 — Resiliência ausente no cliente Portador→Cartão](#melhoria-2--resiliência-ausente-no-cliente-portadorcartão)
- [Melhoria 3 — Handlers de exceção incompletos](#melhoria-3--handlers-de-exceção-incompletos)
- [Melhoria 4 — Cobertura de testes do validador de CPF](#melhoria-4--cobertura-de-testes-do-validador-de-cpf)
- [Melhoria 5 — Política de restart no Docker Compose](#melhoria-5--política-de-restart-no-docker-compose)
- [Melhoria 6 — Contrato OpenAPI desalinhado do código real](#melhoria-6--contrato-openapi-desalinhado-do-código-real)
- [O que foi avaliado e conscientemente deixado de fora](#o-que-foi-avaliado-e-conscientemente-deixado-de-fora)
- [Resultado da validação](#resultado-da-validação)

---

## Bug crítico 1 — Cartão órfão por corrida de CPF duplicado

**Arquivo:** `portador-service/src/main/java/com/cardprocess/portador/application/CardholderService.java`

### Como o problema existia

O código original:

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

O ponto crítico é que `JpaRepository.save()` **não** executa o `INSERT` imediatamente. O Hibernate
apenas registra a entidade como "pendente de persistência" no `PersistenceContext`; o `INSERT` físico
só acontece no *flush* — que, por padrão, ocorre no commit da transação, ao final do método (o
`@Transactional` do Spring fecha a transação depois que o método retorna).

Isso cria uma janela de corrida real:

1. Duas requisições HTTP concorrentes chegam com o **mesmo CPF** (ex.: um cliente que reenvia por
   timeout, ou duas réplicas de um mesmo formulário mal debounced no front).
2. Ambas passam pelo `existsByCpf(cpf)` **antes de qualquer uma commitar** — a constraint de
   unicidade no banco (`cardholder.cpf UNIQUE`) ainda não foi violada porque nenhum `INSERT` foi
   fisicamente executado ainda. As duas leituras retornam `false`.
3. Ambas chamam `repository.save(...)` — que só enfileira o `INSERT` em memória, sem tocar o banco.
4. **Ambas chamam `issuancePublisher.publish(...)`** e enviam uma `IssuanceMessage` para a fila SQS,
   cada uma com um `cardholderId` gerado em memória (o `@GeneratedValue` de UUID já é conhecido antes
   do flush).
5. Só ao final do método, quando a transação tenta commitar, o Hibernate finalmente executa os dois
   `INSERT`. Um deles bate na constraint `UNIQUE(cpf)` do PostgreSQL e sofre rollback.

**Resultado:** a `IssuanceMessage` da requisição perdedora já está na fila, apontando para um
`cardholderId` que **nunca existiu no banco** (o INSERT dele nunca foi efetivado). O Cartão Service
consome essa mensagem, confirma o produto na fonte da verdade, e **persiste um cartão vinculado a um
portador inexistente** — exatamente o cenário de "cartão órfão" que a constituição do projeto proíbe
explicitamente (Princípio II: *Integridade de Dados acima de Disponibilidade nas Escritas*). Pior:
como o card tem `cardholder_id UNIQUE`, esse cartão fantasma passa a **bloquear para sempre** a
emissão de um cartão legítimo para aquele CPF, caso ele seja recadastrado com sucesso depois.

O mesmo padrão existia em `AuthService.register()`, embora sem o efeito colateral de mensageria — lá
o risco era apenas uma resposta de sucesso (`201`) para um usuário que na verdade sofreu rollback.

### Como foi corrigido

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

A troca de `save()` por `saveAndFlush()` força o `INSERT` a ser executado **imediatamente**, ainda
dentro da transação, mas **antes** da chamada a `issuancePublisher.publish(...)`. Isso muda o
resultado da corrida: se dois cadastros concorrentes chegam com o mesmo CPF, o segundo a chegar ao
`flush()` recebe a `DataIntegrityViolationException` **na hora**, o método lança a exceção
imediatamente, e a chamada ao publisher **nunca acontece** para o perdedor. O primeiro segue normal:
flush bem-sucedido, depois publish. Não existe mais janela onde uma mensagem é publicada para um
cardholder que será desfeito.

A mesma correção foi replicada em `AuthService.register()` (`saveAndFlush` no cadastro de usuário),
fechando a janela equivalente ali.

### Por que isso não apareceu nos testes originais

Os testes de integração existentes (`CardholderIssuanceIntegrationTest`) exercitavam apenas o
caminho sequencial — uma requisição de cada vez. A corrida só se manifesta sob concorrência real, que
não fazia parte do escopo de teste anterior. Um teste de integração novo
(`rejectsDuplicateCpfWithConflictAndMaskedDetail`, detalhado na próxima seção) cobre o caminho de
conflito, embora validar a corrida de fato exigiria um teste de concorrência dedicado (duas threads
disputando o mesmo insert) — deixado como próximo passo natural, já que o `saveAndFlush` já fecha a
janela de forma determinística e comprovável por inspeção de código.

---

## Bug 2 — CPF formatado furava a unicidade

**Arquivo:** o mesmo `CardholderService.java`, correção incluída no mesmo commit do Bug 1.

### Como o problema existia

O `CpfValidator` (usado pela anotação `@Cpf` nos DTOs de entrada) aceita CPFs com máscara:

```java
String digits = value.replaceAll("\\D", "");
if (digits.length() != 11 || ...) { return false; }
```

Ou seja, tanto `"39053344705"` quanto `"390.533.447-05"` passam na validação — o validador extrai só
os dígitos para checar os dígitos verificadores. Até aqui, correto.

O problema é que o `CardholderService.register()` **não fazia essa mesma normalização** antes de
persistir. A string exatamente como veio do JSON de entrada era gravada na coluna `cpf VARCHAR(11)` e
usada tanto em `existsByCpf(cpf)` quanto no `Cardholder.register(name, cpf, ...)`. Isso gerava dois
problemas simultâneos:

1. **A constraint `UNIQUE(cpf)` não pegava duplicatas com formatação diferente.** Um cadastro com
   `"39053344705"` e outro com `"390.533.447-05"` são o mesmo CPF, mas o banco os trata como strings
   diferentes — a checagem de unicidade (tanto o `existsByCpf` quanto a constraint SQL) simplesmente
   não detectava a duplicidade.
2. **Risco de truncamento silencioso ou falha de INSERT.** A coluna é `VARCHAR(11)`; uma entrada
   mascarada (`"390.533.447-05"`, 14 caracteres) ultrapassa esse limite. Dependendo da configuração
   do driver JDBC/Postgres, isso pode resultar em erro de `value too long` (uma falha visível, mas
   confusa) em vez do `409 Conflict` esperado para o caso de negócio de "CPF já cadastrado".

### Como foi corrigido

A normalização (`cpf.replaceAll("\\D", "")`) passou a acontecer **uma única vez**, no topo do método
`register()`, e o resultado normalizado é usado tanto na checagem de existência quanto na persistência
(veja o diff completo na seção anterior). Um teste dedicado prova o comportamento:

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

## Bug 3 — Catch morto no consumidor do Cartão

**Arquivo:** `cartao-service/src/main/java/com/cardprocess/cartao/application/CardIssuanceService.java`

### Como o problema existia

```java
try {
    Card card = repository.save(Card.issue(...));
    log.info("Card issued ...");
} catch (DataIntegrityViolationException duplicate) {
    log.info("Concurrent duplicate issuance ignored correlationId={}", message.correlationId());
}
```

A intenção era boa: capturar uma violação de constraint (`correlation_id UNIQUE` ou
`cardholder_id UNIQUE`) causada por reentrega concorrente da mensagem SQS e tratar como um no-op
silencioso, já que a idempotência é garantida pela constraint.

O problema é o mesmo mecanismo do Bug 1: `repository.save()` não executa o `INSERT` na hora — ele
enfileira a operação para o próximo *flush*, que dentro de um método `@Transactional` do Spring
normalmente só ocorre no commit, **depois** que o método já retornou e o bloco `try/catch` já foi
encerrado. Ou seja, na prática esse `catch` **nunca era alcançado** pela violação de constraint na
execução normal do fluxo do Spring Data JPA + Hibernate. Quando a violação de fato ocorria (em uma
mensagem duplicada reentregue exatamente na janela de corrida), ela se manifestava como uma
`UnexpectedRollbackException` — ou o próprio `RollbackException` do JPA no commit da transação, fora
do escopo do `try/catch` do método, e portanto **não seria capturada por aquele catch específico**.

Isso não gerava um bug funcional visível de imediato (a mensagem redistribuída pelo SQS acabaria
protegida por `alreadyProcessed()` numa reentrega futura, já que este *é* verificado antes do save, no
início do método), mas era **código morto enganoso**: um leitor do código concluiria, incorretamente,
que existe uma proteção de idempotência ali dentro do bloco try/catch, quando na verdade a única
proteção real e efetiva é o `alreadyProcessed()` no topo do método, combinado com a natureza
*at-least-once* + redelivery do SQS.

### Como foi corrigido

O bloco `try/catch` foi removido, deixando o código refletir a real fonte de garantia de idempotência:

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

A idempotência continua garantida por `alreadyProcessed()` (checagem por `correlation_id` /
`cardholder_id` antes de qualquer tentativa de escrita) somada à constraint `UNIQUE` do banco como
rede de segurança final — mas agora sem um bloco de código que sugere uma proteção que não existia de
fato.

---

## Melhoria 1 — Vazamento de CPF no corpo do erro (LGPD)

**Arquivo:** `portador-service/src/main/java/com/cardprocess/portador/domain/DomainExceptions.java`

### O que existia

```java
public static class DuplicateCpfException extends RuntimeException {
    public DuplicateCpfException(String cpf) {
        super("Cardholder already registered for CPF: " + cpf);
    }
}
```

Essa mensagem alimenta diretamente o campo `detail` do `ProblemDetail` retornado ao cliente HTTP em
caso de `409 Conflict` (via `GlobalExceptionHandler.handleConflict`). Um CPF é um dado pessoal
identificável sob a LGPD (Lei Geral de Proteção de Dados) — expor o número completo num corpo de erro
HTTP é um vazamento de dado sensível desnecessário: o cliente da API já sabe o CPF que enviou, então
ecoá-lo de volta não agrega valor funcional, apenas aumenta a superfície de exposição (logs de acesso,
proxies intermediários, ferramentas de observabilidade que capturam corpos de resposta, etc. passam a
reter o CPF completo).

### Como foi corrigido

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

A mensagem de erro agora expõe apenas os dois últimos dígitos (`*********05`), suficiente para o
usuário confirmar qual CPF colidiu sem expor o dado completo. Um teste de integração ponta a ponta
prova o comportamento observável via HTTP:

```java
MvcResult conflict = mockMvc.perform(post("/cardholders")...)
        .andExpect(status().isConflict())
        .andReturn();
String detail = objectMapper.readTree(conflict.getResponse().getContentAsString()).get("detail").asText();
assertThat(detail).doesNotContain("52998224725");
```

---

## Melhoria 2 — Resiliência ausente no cliente Portador→Cartão

**Arquivo:** `portador-service/src/main/java/com/cardprocess/portador/infrastructure/client/CartaoClient.java`

### O que existia

A constituição do projeto (Princípio I — *Resiliência Primeiro*) exige explicitamente que "toda
chamada entre serviços ou infraestrutura assume que a dependência pode falhar" e que integrações
síncronas sejam "envolvidas com timeouts, retry com backoff exponencial e circuit breaker". Essa regra
já havia sido aplicada corretamente na única outra chamada síncrona entre serviços do sistema —
`ProdutoServiceClient` (Cartão → Produto), que usa `@CircuitBreaker` + `@Retry` do Resilience4j.

Só que existe uma **segunda** chamada síncrona entre serviços no sistema: `CartaoClient`
(Portador → Cartão), usada por `AggregationService` para montar a resposta de
`GET /cardholders/{id}/aggregate`. Essa chamada tinha apenas timeouts de conexão/leitura
(`connectTimeout`/`readTimeout` de 2s/3s), sem retry nem circuit breaker:

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

Isso significa que uma falha **transitória** (um timeout de rede pontual, uma reinicialização rápida
do Cartão Service, um GC pause) já derrubava a consulta agregada na primeira tentativa, sem chance de
recuperação automática — inconsistente com o padrão de resiliência que o próprio projeto define e já
aplica em outro lugar.

### Como foi corrigido

```java
private static final String RESILIENCE_INSTANCE = "cartaoService";

@Override
@CircuitBreaker(name = RESILIENCE_INSTANCE)
@Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "findByCardholderFallback")
public Optional<CardView> findByCardholder(UUID cardholderId) {
    ... // corpo inalterado
}

@SuppressWarnings("unused")
private Optional<CardView> findByCardholderFallback(UUID cardholderId, Throwable cause) {
    throw new CardServiceUnavailableException(cause);
}
```

Além da dependência `resilience4j-spring-boot3` (já presente no `pom.xml` raiz, faltava apenas
declará-la no módulo Portador) e `spring-boot-starter-aop` (necessário para os proxies AOP das
anotações do Resilience4j funcionarem), configurou-se a instância `cartaoService` no
`application.yml`, com os mesmos parâmetros já usados para `produtoService` no Cartão Service (3
tentativas, backoff exponencial a partir de 200ms, circuit breaker com janela de 10 chamadas e limiar
de falha de 50%):

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

Agora **as duas** chamadas síncronas entre serviços do sistema (Cartão→Produto e Portador→Cartão)
seguem exatamente o mesmo padrão de resiliência, fechando a inconsistência.

---

## Melhoria 3 — Handlers de exceção incompletos

**Arquivos:** os três `GlobalExceptionHandler.java` (Produto, Portador, Cartão).

### O que existia

Cada `@RestControllerAdvice` tratava apenas as exceções de domínio conhecidas (`ProductNotFoundException`,
`CardholderNotFoundException`, etc.) e `MethodArgumentNotValidException` (erros de `@Valid`). Não havia
nenhum handler para o caso genérico — **qualquer exceção não prevista** (um `NullPointerException`
por um bug futuro, uma falha de conexão inesperada com o banco fora dos caminhos já tratados,
qualquer coisa que a lista explícita de `@ExceptionHandler` não cobrisse) caía no comportamento padrão
do Spring: uma resposta `500` com o corpo de erro whitelabel padrão, potencialmente vazando detalhes
de stack trace dependendo da configuração de `server.error.include-*`, e sem nenhum log estruturado
específico da aplicação apontando para o problema.

Isso contraria o Princípio I da constituição — "uma dependência degradada DEVE aparecer como um status
HTTP semântico ou uma mensagem retentável, nunca como um `500` não tratado" — e o próprio README do
projeto, que promete que "um handler global de exceção mapeia cada erro para um status HTTP semântico
... nunca um stack trace bruto". Essa promessa só era cumprida para as exceções explicitamente
listadas.

Adicionalmente, o Portador Service não tratava `DataIntegrityViolationException` (que passou a ser
relevante depois da correção do Bug 1) — sem esse handler, uma violação de constraint de unicidade não
capturada explicitamente em algum service resultaria em `500` em vez do `409 Conflict` correto.

### Como foi corrigido

Os três handlers passaram a estender `ResponseEntityExceptionHandler` (a classe base do Spring MVC
para tratamento centralizado de exceções, que já sabe como interceptar exceções internas do framework
como `MethodArgumentNotValidException` via *override* em vez de `@ExceptionHandler` solto) e ganharam
um handler catch-all:

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ... handlers específicos de domínio inalterados ...

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

No Portador Service, adicionou-se ainda o handler específico para `DataIntegrityViolationException`
(`409 Conflict`, log em nível `WARN`), cobrindo qualquer violação de unicidade que escape das
checagens explícitas de `existsByCpf`/`existsByUsername` (por exemplo, sob concorrência extrema, ou
em pontos futuros de código que ainda não fazem a checagem prévia).

Com essa mudança, a garantia "todo erro vira `application/problem+json`, nunca um stack trace" passou
a ser **estruturalmente verdadeira** (qualquer `Exception` cai no catch-all) em vez de depender de
cada desenvolvedor lembrar de adicionar um `@ExceptionHandler` para cada nova classe de exceção.

---

## Melhoria 4 — Cobertura de testes do validador de CPF

**Arquivo novo:** `portador-service/src/test/java/com/cardprocess/portador/web/validation/CpfValidatorTest.java`

### O que faltava

O `CpfValidator` (algoritmo de dígitos verificadores de CPF, usado pela anotação Bean Validation
`@Cpf`) não tinha nenhum teste unitário dedicado. Sua única cobertura indireta vinha dos testes de
integração que usam CPFs de exemplo já válidos (`"39053344705"`) — ou seja, o algoritmo em si (cálculo
dos dois dígitos verificadores, rejeição de sequências repetidas como `"11111111111"`, rejeição de
tamanho incorreto) nunca era exercitado contra casos de borda ou entradas inválidas.

Isso é relevante porque é uma peça de lógica de negócio com múltiplos caminhos condicionais
(tamanho errado, todos os dígitos iguais, primeiro dígito verificador errado, segundo dígito
verificador errado, entrada nula) — exatamente o tipo de código onde um teste de unidade barato
detecta regressões que um teste de integração, focado no caminho feliz, não pegaria.

### O que foi adicionado

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
            "39053344704",   // primeiro dígito verificador errado
            "39053344715",   // segundo dígito verificador errado
            "11111111111",   // todos os dígitos iguais
            "00000000000",   // todos os dígitos iguais
            "1234567890",    // curto demais
            "123456789012",  // longo demais
            "abcdefghijk",   // não numérico
            ""                // vazio
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

12 casos parametrizados cobrindo: CPF válido puro, CPF válido mascarado, cada um dos dois dígitos
verificadores incorretos isoladamente, sequências repetidas, tamanhos incorretos (curto e longo),
entrada não numérica, string vazia e `null`.

---

## Melhoria 5 — Política de restart no Docker Compose

**Arquivo:** `docker-compose.yml`

### O que existia

Nenhum dos 6 serviços (`postgres`, `redis`, `localstack`, `produto-service`, `portador-service`,
`cartao-service`) declarava uma política de `restart`. O comportamento padrão do Docker nesse caso é
`no` — se um contêiner cair (crash da JVM, OOM killer, exceção não tratada na inicialização de um
bean, uma falha momentânea de rede que derruba a conexão do driver e mata o processo), ele **fica
parado indefinidamente** até uma intervenção manual (`docker compose up` de novo).

Isso conflita diretamente com o enquadramento do próprio projeto — o README descreve a Portador/Cartão
como parte de uma "plataforma do setor financeiro... que nunca deve deixar um portador sem resposta" —
e é uma lacuna óbvia de operação: um ecossistema pensado para tolerar falhas de *dependências
externas* (Redis, Produto Service, SQS) não fazia nada para se recuperar de uma falha do **próprio
processo**.

### Como foi corrigido

Adicionou-se `restart: unless-stopped` a cada um dos 6 serviços do `docker-compose.yml`:

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
  # (mesma linha adicionada em localstack, produto-service, portador-service, cartao-service)
```

`unless-stopped` foi escolhido (em vez de `always`) porque respeita uma parada manual explícita do
operador (`docker compose stop <serviço>`) — que é exatamente o mecanismo usado pelos próprios
cenários de demonstração de resiliência do README (`docker compose stop redis`,
`docker compose stop produto-service`) para simular indisponibilidade sob controle. Com `always`,
esses comandos de demonstração teriam o contêiner reiniciado automaticamente pelo Docker antes do
operador conseguir observar o comportamento degradado — o oposto do que se quer demonstrar.

---

## Melhoria 6 — Contrato OpenAPI desalinhado do código real

**Arquivo:** `specs/001-card-processing-ecosystem/contracts/produto-openapi.yaml`

### O que existia

O contrato OpenAPI do Produto Service (artefato de design do spec-kit, parte da documentação de
contratos gerada na Fase 1 do planejamento) descrevia a operação de cancelamento como:

```yaml
/products/{id}:
  patch:
    summary: Cancel a product
    operationId: cancelProduct
```

Ou seja, um `PATCH` no mesmo caminho usado pelo `GET` (`/products/{id}`). Mas o controller
efetivamente implementado usa um sub-recurso dedicado:

```java
@PatchMapping("/{id}/cancel")
public ProductResponse cancel(@PathVariable UUID id) { ... }
```

Isto é, o caminho real é `PATCH /products/{id}/cancel`, não `PATCH /products/{id}`. Esse desalinhamento
é o tipo de *drift* de documentação que surge naturalmente durante a implementação (uma decisão de
design mudou depois que o contrato foi escrito) mas que, se não corrigido, mina a confiabilidade dos
artefatos de contrato como fonte de verdade — alguém integrando com base no YAML tentaria `PATCH
/products/{id}` e receberia um `405 Method Not Allowed` ou comportamento inesperado, já que esse
caminho só aceita `GET` no controller real.

### Como foi corrigido

O contrato foi ajustado para refletir o caminho real, movendo a operação de `PATCH` para sua própria
entrada `/products/{id}/cancel`:

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

O README também foi atualizado (tabela de referência de API e matriz de resiliência) para
incorporar as novas garantias descritas nas seções anteriores (retry/circuit breaker no cliente
Portador→Cartão, prevenção de corrida na escrita, mascaramento de CPF, `restart: unless-stopped`).

---

## O que foi avaliado e conscientemente deixado de fora

Nem toda melhoria identificada durante a revisão foi implementada — algumas foram avaliadas e
descartadas por estarem fora do escopo proposto ou por exigirem uma mudança arquitetural maior do
que o hardening pontual proposto:

- **Outbox transacional no Portador Service.** O `saveAndFlush` (Bug 1) fecha a janela de corrida
  entre requisições concorrentes, mas não elimina uma janela residual menor: se o `INSERT` do
  cardholder for bem-sucedido e a transação commitar, mas o processo cair (crash da JVM, falha de rede
  para o SQS) *entre* o commit e a chamada a `issuancePublisher.publish(...)`, a mensagem de emissão
  nunca é enviada e o cardholder fica cadastrado sem cartão, permanentemente, sem nenhum mecanismo de
  recuperação automática. A solução definitiva para esse tipo de problema é o padrão *Transactional
  Outbox* (a mensagem é gravada na mesma transação do cardholder, numa tabela de outbox, e um processo
  assíncrono separado — ou um *Change Data Capture* como Debezium — garante a entrega efetiva à fila).
  Isso já estava documentado como evolução natural no README original e permanece como tal: é uma
  mudança de infraestrutura de mensageria, não um bug pontual, e está fora do escopo desta revisão de
  hardening. *(Nota: essa lacuna foi fechada posteriormente — veja
  [implementation-walkthrough.pt.md § 11](implementation-walkthrough.pt.md#11-hardening-pós-revisão-outbox-transacional)
  para o outbox transacional implementado depois.)*
- **Autenticação nos serviços Produto e Cartão.** Ambos permanecem sem JWT, por design — o escopo
  especifica autenticação apenas no Portador Service (o único ponto de entrada voltado a operadores
  humanos); Produto e Cartão são chamados apenas internamente, por outros serviços da malha.
- **Observabilidade avançada (tracing distribuído, métricas customizadas).** Mencionada no README como
  evolução natural (Micrometer + tracing), mas não implementada nesta revisão — não foi identificado
  como um bug ou lacuna crítica, apenas como um ganho de maturidade operacional de mais longo prazo.
- **Teste de concorrência dedicado para o Bug 1.** A correção (`saveAndFlush`) é comprovável por
  inspeção de código (o flush força o INSERT antes do publish, fechando a janela por construção), mas
  um teste que dispara duas requisições verdadeiramente concorrentes (via `ExecutorService` com duas
  threads reais batendo no mesmo endpoint) daria uma prova empírica adicional. Ficou fora desta rodada
  por ser um teste de infraestrutura mais custoso (flaky por natureza se malfeito) e não essencial para
  validar a correção, já que a causa raiz (ordem de execução do flush) é determinística e não
  probabilística.

---

## Resultado da validação

Após todas as correções, a suíte completa de testes (`mvn test`, unitários + integração via
Testcontainers com Postgres, Redis e LocalStack reais) foi executada do zero:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0   -- produto-service
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  -- portador-service
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  -- cartao-service
[INFO] BUILD SUCCESS
```

**34 testes verdes** (eram 20 antes desta revisão) — o aumento de 14 testes vem inteiramente da nova
cobertura: 12 casos do `CpfValidatorTest`, 1 teste de normalização de CPF formatado, e 1 teste de
integração ponta a ponta do fluxo de conflito de CPF duplicado com verificação de mascaramento no
corpo da resposta HTTP.

Todas as mudanças foram commitadas em 8 commits atômicos no branch `worktree-hardening-review`,
agrupados logicamente por problema/melhoria, prontos para revisão e merge em `main`.
