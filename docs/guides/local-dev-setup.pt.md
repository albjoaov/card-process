# Configuração de Desenvolvimento Local

*[English](local-dev-setup.md)*

Este guia cobre a execução dos três serviços localmente (na JVM), mantendo as dependências de infraestrutura (PostgreSQL, Redis, LocalStack) no Docker. Isso evita ciclos de rebuild/restart a cada mudança de código.

## Pré-requisitos

- Java 21 (via SDKMAN: `sdk install java 21-tem`)
- Maven: não é necessário — o repositório inclui o Maven wrapper (`./mvnw`), que baixa o Maven 3.9.9 no primeiro uso
- Docker (OrbStack ou Docker Desktop)

## Passo 1 — Subir só a infraestrutura

Suba apenas os contêineres de infra, sem os contêineres dos serviços:

```bash
docker compose up postgres redis localstack -d
```

Espere os três ficarem saudáveis:

```bash
docker compose ps
```

Os três devem mostrar `healthy` antes de continuar.

## Passo 2 — Build do projeto

A partir da raiz do repositório, faça o build de todos os módulos (pulando os testes para agilizar):

```bash
./mvnw clean install -DskipTests
```

## Passo 3 — Rodar os serviços

O `application.yml` de cada serviço já usa `localhost` como padrão para todas as variáveis de ambiente, então nenhuma configuração extra é necessária para desenvolvimento local.

> **Hot-reload:** o `spring-boot:run` já usa o Spring Boot DevTools automaticamente. Ao salvar e recompilar um arquivo, o DevTools dispara um restart rápido do serviço afetado (tipicamente menos de 2 segundos). Para um ciclo ainda mais curto, use sua IDE (veja [Configuração de IDE](#configuração-de-ide) abaixo) — ela compila incrementalmente ao salvar, então os restarts do DevTools acontecem sem intervenção manual.

Abra três abas de terminal e rode cada serviço independentemente:

**Aba 1 — produto-service** (porta 8081)
```bash
./mvnw -pl produto-service spring-boot:run
```

**Aba 2 — cartao-service** (porta 8083)
```bash
./mvnw -pl cartao-service spring-boot:run
```

**Aba 3 — portador-service** (porta 8082)
```bash
./mvnw -pl portador-service spring-boot:run
```

> Suba `produto-service` e `cartao-service` antes do `portador-service`, já que o portador chama o cartão durante a emissão do cartão.

## Passo 4 — Verificar

Confira o endpoint de saúde de cada serviço:

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

As Swagger UIs estão disponíveis em:
- `http://localhost:8081/swagger-ui.html`
- `http://localhost:8082/swagger-ui.html`
- `http://localhost:8083/swagger-ui.html`

## Iteração no dia a dia

Depois de mudar código em um serviço, basta reiniciar o processo `spring-boot:run` daquele serviço — sem precisar de rebuild do Docker. Os outros serviços e os contêineres de infra continuam rodando sem interrupção.

Para reiniciar tudo do zero:

```bash
docker compose down -v   # remove os volumes também, apagando os dados do banco
docker compose up postgres redis localstack -d
# depois reinicie cada serviço
```

## Configuração de IDE

Rodar os serviços a partir de uma IDE dá compilação incremental e JVM HotSwap para mudanças no corpo de métodos — sem precisar de restart completo na maioria das edições.

### IntelliJ IDEA (Community ou Ultimate)

1. **Abra o projeto:** `File → Open` → selecione a raiz do repositório (`pom.xml`). O IntelliJ vai importar como um projeto Maven multi-módulo.

2. **Crie uma Run Configuration para cada serviço:**
   - Vá em `Run → Edit Configurations → + → Spring Boot`
   - **Name:** `produto-service`
   - **Main class:** `com.cardprocess.produto.ProdutoServiceApplication`
   - **Module:** `produto-service`
   - Repita para `cartao-service` (`com.cardprocess.cartao.CartaoServiceApplication`) e `portador-service` (`com.cardprocess.portador.PortadorServiceApplication`)

3. **Ative a recompilação automática (HotSwap):**
   - `Settings → Build, Execution, Deployment → Compiler` → marque **Build project automatically**
   - `Settings → Advanced Settings` → marque **Allow auto-make to start even if developed application is currently running**
   - Com essas opções, o IntelliJ recompila os arquivos alterados ao salvar e o JVM HotSwap substitui corpos de método sem restart completo. Mudanças estruturais (novos campos, novas classes) ainda exigem restart manual.

4. Rode cada configuração pelo botão **Run** (▶) ou `Shift+F10`.

> O IntelliJ Ultimate também suporta live reload completo com reconhecimento do Spring via seu agente HotSwap embutido — sem configuração extra.

---

### VS Code / Cursor

1. **Instale o Extension Pack for Java:**
   - Procure por `vscjava.vscode-java-pack` no painel de Extensions e instale. Isso inclui as extensões Language Support for Java, Debugger for Java e Maven for Java.

2. **Abra a pasta raiz do repositório.** O VS Code vai detectar o projeto Maven automaticamente e indexá-lo.

3. **Rode um serviço:**
   - Abra o arquivo da classe principal, ex.: `produto-service/src/main/java/com/cardprocess/produto/ProdutoServiceApplication.java`
   - Clique no CodeLens **Run** que aparece acima do método `main`, ou pressione `F5`.
   - Alternativamente, use o painel **Spring Boot Dashboard** (disponível após instalar a extensão `vscjava.vscode-spring-boot-dashboard`) para iniciar/parar cada serviço em um único painel.

4. **Hot-reload:** o Spring Boot DevTools já está no projeto. O VS Code vai disparar um restart automático do serviço sempre que você salvar um arquivo `.java` e a extensão recompilá-lo. Sem configuração extra.

5. **launch.json (opcional, para args de JVM customizados ou overrides de env):**
   Crie `.vscode/launch.json` na raiz do repositório:
   ```json
   {
     "version": "0.2.0",
     "configurations": [
       {
         "type": "java",
         "name": "produto-service",
         "request": "launch",
         "mainClass": "com.cardprocess.produto.ProdutoServiceApplication",
         "projectName": "produto-service"
       },
       {
         "type": "java",
         "name": "cartao-service",
         "request": "launch",
         "mainClass": "com.cardprocess.cartao.CartaoServiceApplication",
         "projectName": "cartao-service"
       },
       {
         "type": "java",
         "name": "portador-service",
         "request": "launch",
         "mainClass": "com.cardprocess.portador.PortadorServiceApplication",
         "projectName": "portador-service"
       }
     ]
   }
   ```

---

## Referência de variáveis de ambiente

Todos os valores abaixo já são os padrões em `application.yml`. Só defina-os explicitamente se precisar sobrescrever.

| Variável | Padrão | Usada por |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/<db>` | todos os serviços |
| `SPRING_DATASOURCE_USERNAME` | `cardprocess` | todos os serviços |
| `SPRING_DATASOURCE_PASSWORD` | `cardprocess` | todos os serviços |
| `SPRING_DATA_REDIS_HOST` | `localhost` | cartao-service |
| `SPRING_DATA_REDIS_PORT` | `6379` | cartao-service |
| `SPRING_CLOUD_AWS_SQS_ENDPOINT` | `http://localhost:4566` | cartao-service, portador-service |
| `SPRING_CLOUD_AWS_REGION_STATIC` | `us-east-1` | cartao-service, portador-service |
| `SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY` | `test` | cartao-service, portador-service |
| `SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY` | `test` | cartao-service, portador-service |
| `CARDPROCESS_ISSUANCE_QUEUE` | `card-issuance-queue` | cartao-service, portador-service |
| `CARDPROCESS_CARTAO_BASE_URL` | `http://localhost:8083` | portador-service |
| `CARDPROCESS_PRODUTO_BASE_URL` | `http://localhost:8081` | cartao-service |
| `CARDPROCESS_PRODUCT_CACHE_TTL` | `PT10M` | cartao-service |
| `CARDPROCESS_SECURITY_JWT_SECRET` | `card-process-super-secret-signing-key-change-me` | portador-service |
