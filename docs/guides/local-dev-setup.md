# Local Development Setup

*[Português](local-dev-setup.pt.md)*

This guide covers running the three services locally (on the JVM) while keeping infrastructure dependencies (PostgreSQL, Redis, LocalStack) in Docker. This avoids rebuild/restart cycles on every code change.

## Prerequisites

- Java 21 (via SDKMAN: `sdk install java 21-tem`)
- Maven: not required — the repo ships the Maven wrapper (`./mvnw`), which downloads Maven 3.9.9 on first use
- Docker (OrbStack or Docker Desktop)

## Step 1 — Start infrastructure only

Bring up only the infra containers, skipping the service containers:

```bash
docker compose up postgres redis localstack -d
```

Wait for all three to be healthy:

```bash
docker compose ps
```

All three should show `healthy` before proceeding.

## Step 2 — Build the project

From the repo root, build all modules (skipping tests for speed):

```bash
./mvnw clean install -DskipTests
```

## Step 3 — Run the services

The `application.yml` of each service already defaults all environment variables to `localhost`, so no extra env configuration is needed for local development.

> **Hot-reload:** `spring-boot:run` picks up Spring Boot DevTools automatically. When you save and recompile a file, DevTools triggers a fast restart of the affected service (typically under 2 seconds). For an even tighter loop, use your IDE (see [IDE Setup](#ide-setup) below) — it compiles incrementally on save, so DevTools restarts happen without manual intervention.

Open three terminal tabs and run each service independently:

**Tab 1 — produto-service** (port 8081)
```bash
./mvnw -pl produto-service spring-boot:run
```

**Tab 2 — cartao-service** (port 8083)
```bash
./mvnw -pl cartao-service spring-boot:run
```

**Tab 3 — portador-service** (port 8082)
```bash
./mvnw -pl portador-service spring-boot:run
```

> Start `produto-service` and `cartao-service` before `portador-service`, since portador calls cartao during card issuance.

## Step 4 — Verify

Check each service health endpoint:

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

Swagger UIs are available at:
- `http://localhost:8081/swagger-ui.html`
- `http://localhost:8082/swagger-ui.html`
- `http://localhost:8083/swagger-ui.html`

## Day-to-day iteration

After changing code in a service, just restart that service's `spring-boot:run` process — no Docker rebuild needed. The other services and infra containers keep running undisturbed.

To restart everything from scratch:

```bash
docker compose down -v   # removes volumes too, wiping DB data
docker compose up postgres redis localstack -d
# then restart each service
```

## IDE Setup

Running services from an IDE gives you incremental compilation and JVM HotSwap for method-body changes — no full restart needed for most edits.

### IntelliJ IDEA (Community or Ultimate)

1. **Open the project:** `File → Open` → select the repo root (`pom.xml`). IntelliJ will import it as a Maven multi-module project.

2. **Create a Run Configuration for each service:**
   - Go to `Run → Edit Configurations → + → Spring Boot`
   - **Name:** `produto-service`
   - **Main class:** `com.cardprocess.produto.ProdutoServiceApplication`
   - **Module:** `produto-service`
   - Repeat for `cartao-service` (`com.cardprocess.cartao.CartaoServiceApplication`) and `portador-service` (`com.cardprocess.portador.PortadorServiceApplication`)

3. **Enable automatic recompilation (HotSwap):**
   - `Settings → Build, Execution, Deployment → Compiler` → check **Build project automatically**
   - `Settings → Advanced Settings` → check **Allow auto-make to start even if developed application is currently running**
   - With these options, IntelliJ recompiles changed files on save and JVM HotSwap replaces method bodies without a full restart. Structural changes (new fields, new classes) still require a manual restart.

4. Run each configuration via the **Run** button (▶) or `Shift+F10`.

> IntelliJ Ultimate also supports full Spring-aware live reload via its built-in HotSwap agent — no extra setup needed.

---

### VS Code / Cursor

1. **Install the Extension Pack for Java:**
   - Search for `vscjava.vscode-java-pack` in the Extensions panel and install it. This includes the Language Support for Java, Debugger for Java, and Maven for Java extensions.

2. **Open the repo root folder.** VS Code will detect the Maven project automatically and index it.

3. **Run a service:**
   - Open the main class file, e.g. `produto-service/src/main/java/com/cardprocess/produto/ProdutoServiceApplication.java`
   - Click the **Run** CodeLens that appears above the `main` method, or press `F5`.
   - Alternatively, use the **Spring Boot Dashboard** panel (available after installing the `vscjava.vscode-spring-boot-dashboard` extension) to start/stop each service from a single panel.

4. **Hot-reload:** Spring Boot DevTools is already in the project. VS Code will trigger an automatic service restart whenever you save a `.java` file and the extension recompiles it. No extra setup needed.

5. **launch.json (optional, for custom JVM args or env overrides):**
   Create `.vscode/launch.json` in the repo root:
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

## Environment variable reference

All values below are already the defaults in `application.yml`. Only set them explicitly if you need to override.

| Variable | Default | Used by |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/<db>` | all services |
| `SPRING_DATASOURCE_USERNAME` | `cardprocess` | all services |
| `SPRING_DATASOURCE_PASSWORD` | `cardprocess` | all services |
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
