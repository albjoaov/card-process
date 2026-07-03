# Guia de Uso do Spec Kit

O Spec Kit é um conjunto de skills que guia o processo de criação de features de forma estruturada, do conceito até a implementação. Cada skill é um passo no pipeline.

## Pipeline Completo

```
/specify → /clarify → /plan → /tasks → /analyze → /implement
                                                 ↑
                                           /converge (se parado no meio)
```

---

## Estrutura de Arquivos

Ao trabalhar com uma feature, o spec-kit cria e mantém os seguintes arquivos em `specs/<nome-da-feature>/`:

```
specs/
└── 001-nome-da-feature/
    ├── spec.md          # Especificação funcional
    ├── plan.md          # Plano técnico de implementação
    ├── tasks.md         # Lista de tarefas ordenadas por dependência
    ├── data-model.md    # Modelo de dados (gerado pelo /plan)
    ├── research.md      # Pesquisa técnica (gerado pelo /plan)
    └── contracts/       # Contratos de API (gerado pelo /plan)
```

A feature ativa é rastreada em `.specify/feature.json`.

---

## Skills Disponíveis

### `/speckit-specify` — Especificar a Feature

Cria ou atualiza o `spec.md` a partir de uma descrição em linguagem natural.

```
/speckit-specify Adicionar endpoint de consulta de saldo do cartão com cache Redis
```

**O que faz:**
- Cria o diretório da feature em `specs/`
- Gera `spec.md` com contexto, requisitos funcionais e não-funcionais, critérios de aceite e casos de borda
- Atualiza `.specify/feature.json` com o diretório ativo

**Quando usar:** Início de qualquer feature nova, ou para atualizar requisitos.

---

### `/speckit-clarify` — Clarificar Requisitos

Analisa o `spec.md` atual e faz até 5 perguntas direcionadas para preencher lacunas, depois incorpora as respostas na spec.

```
/speckit-clarify
/speckit-clarify foco em autenticação e rate limiting
```

**Quando usar:** Após o `/specify`, antes de planejar. Evita ambiguidades que causam retrabalho.

---

### `/speckit-plan` — Planejar a Implementação

Gera os artefatos de design técnico baseados no `spec.md`.

```
/speckit-plan
/speckit-plan priorizar compatibilidade com a arquitetura existente de filas SQS
```

**O que faz:**
- Gera `plan.md` com decisões arquiteturais, componentes e estratégia de implementação
- Pode gerar `data-model.md`, `research.md` e arquivos em `contracts/`

**Quando usar:** Após clarificar a spec. É a etapa mais longa.

---

### `/speckit-tasks` — Gerar Tarefas

Converte o `plan.md` em uma lista de tarefas ordenada por dependência no `tasks.md`.

```
/speckit-tasks
/speckit-tasks máximo 20 tarefas, agrupar por serviço
```

**O que gera:** Tarefas com ID, descrição, dependências, arquivos afetados e critérios de conclusão.

**Quando usar:** Após o `/plan`. Pré-requisito para o `/implement`.

---

### `/speckit-analyze` — Analisar Consistência

Verifica consistência entre `spec.md`, `plan.md` e `tasks.md` sem alterar nada.

```
/speckit-analyze
/speckit-analyze verificar se todos os requisitos da spec têm tarefas correspondentes
```

**O que verifica:**
- Requisitos da spec sem tarefas cobrindo-os
- Tarefas sem conexão com o plano
- Dependências circulares ou ordenação incorreta

**Quando usar:** Após o `/tasks`, antes do `/implement`. É não-destrutivo — só reporta.

---

### `/speckit-implement` — Implementar

Executa todas as tarefas do `tasks.md` em ordem, marcando cada uma como concluída.

```
/speckit-implement
/speckit-implement apenas as tarefas do portador-service
```

**O que faz:** Lê cada tarefa, escreve o código, roda testes quando aplicável, e marca a tarefa como `done`.

**Quando usar:** Quando spec, plan e tasks estão revisados e aprovados.

---

### `/speckit-converge` — Retomar Implementação Parada

Compara o estado atual do código com spec/plan/tasks e adiciona ao `tasks.md` as partes ainda não implementadas.

```
/speckit-converge
```

**Quando usar:** Se o `/implement` foi interrompido no meio, ou se você fez mudanças manuais e quer que o spec-kit "alcance" o que falta.

---

### `/speckit-checklist` — Checklist Customizado

Gera um checklist de verificação específico para a feature atual.

```
/speckit-checklist
/speckit-checklist foco em segurança e observabilidade
```

---

### `/speckit-taskstoissues` — Exportar para GitHub Issues

Converte as tarefas do `tasks.md` em GitHub Issues.

```
/speckit-taskstoissues
```

---

## Fluxo Recomendado para uma Feature Nova

```bash
# 1. Descrever o que quer construir
/speckit-specify Implementar endpoint de bloqueio de cartão com notificação por SQS

# 2. Refinar requisitos ambíguos (opcional mas recomendado)
/speckit-clarify

# 3. Planejar a arquitetura técnica
/speckit-plan

# 4. Gerar as tarefas de implementação
/speckit-tasks

# 5. Verificar consistência entre os artefatos
/speckit-analyze

# 6. Implementar
/speckit-implement
```

---

## Fluxo para Retomar Feature Interrompida

```bash
# Ver o que já foi feito vs. o que falta
/speckit-converge

# Continuar a implementação
/speckit-implement
```

---

## Dicas

- **Cada skill opera sobre a feature ativa** definida em `.specify/feature.json`. Se precisar trocar de feature, rode `/speckit-specify` novamente com o nome da outra feature.
- **`/clarify` economiza tempo**: quanto mais clara a spec, menos vai-e-vem durante o `/implement`.
- **`/analyze` é gratuito**: não muda nada, só reporta. Use sempre antes de implementar.
- **Você pode editar `spec.md` e `plan.md` manualmente** — o spec-kit vai respeitar o conteúdo editado nas próximas etapas.
