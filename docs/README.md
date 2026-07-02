# Documentação

Índice da documentação do Card Process. A visão geral de arquitetura, setup e
API está no [`README.md`](../README.md) da raiz; os artefatos de especificação (spec, plan,
research, tasks, contracts) estão em [`specs/001-card-processing-ecosystem/`](../specs/001-card-processing-ecosystem/).

## [`guias/`](guias/) — como rodar, desenvolver e implantar

- [`local-dev-setup.md`](guias/local-dev-setup.md) — desenvolvimento local: serviços na JVM, infra no Docker, setup de IDE.
- [`deploy-vps-coolify.md`](guias/deploy-vps-coolify.md) — runbook de produção: VPS única com Coolify, ElasticMQ, TLS, backups.
- [`spec-kit-guide.md`](guias/spec-kit-guide.md) — o pipeline spec-kit usado no desenvolvimento (`/specify → /plan → /tasks → /implement`).

## [`relatorios/`](relatorios/) — como o sistema foi construído

- [`implementation-walkthrough.md`](relatorios/implementation-walkthrough.md) — passo a passo da construção (inglês).
- [`relatorio-de-implementacao.md`](relatorios/relatorio-de-implementacao.md) — relato cronológico em português, incluindo erros e correções.
- [`relatorio-de-hardening.md`](relatorios/relatorio-de-hardening.md) — revisão crítica pós-implementação: bugs encontrados, antes/depois de cada correção.
