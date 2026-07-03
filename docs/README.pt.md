# Documentação

*[English](README.md)*

Índice da documentação do Card Process. A visão geral de arquitetura, setup e
API está no [`README.md`](../README.pt.md) da raiz; os artefatos de especificação (spec, plan,
research, tasks, contracts) estão em [`specs/001-card-processing-ecosystem/`](../specs/001-card-processing-ecosystem/).

Todo guia e relatório abaixo está disponível em inglês e português (sufixo `.pt.md`).

## [`guides/`](guides/) — como rodar, desenvolver e implantar

- [`local-dev-setup.pt.md`](guides/local-dev-setup.pt.md) — desenvolvimento local: serviços na JVM, infra no Docker, setup de IDE.
- [`deploy-vps-coolify.pt.md`](guides/deploy-vps-coolify.pt.md) — runbook de produção: VPS única com Coolify, ElasticMQ, TLS, backups.
- [`spec-kit-guide.pt.md`](guides/spec-kit-guide.pt.md) — o pipeline spec-kit usado no desenvolvimento (`/specify → /plan → /tasks → /implement`).

## [`reports/`](reports/) — como o sistema foi construído

- [`implementation-walkthrough.pt.md`](reports/implementation-walkthrough.pt.md) — passo a passo da construção, incluindo os erros encontrados ao longo do caminho e como cada um foi corrigido.
- [`hardening-report.pt.md`](reports/hardening-report.pt.md) — revisão crítica pós-implementação: bugs encontrados, antes/depois de cada correção.
