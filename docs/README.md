# Documentation

*[Português](README.pt.md)*

Index of the Card Process documentation. The architecture, setup and API overview lives in the
root [`README.md`](../README.md); the specification artifacts (spec, plan, research, tasks,
contracts) live in [`specs/001-card-processing-ecosystem/`](../specs/001-card-processing-ecosystem/).

Every guide and report below is written in both English and Portuguese (`.pt.md` suffix).

## [`guides/`](guides/) — how to run, develop and deploy

- [`local-dev-setup.md`](guides/local-dev-setup.md) — local development: services on the JVM, infra in Docker, IDE setup.
- [`deploy-vps-coolify.md`](guides/deploy-vps-coolify.md) — production runbook: single VPS with Coolify, ElasticMQ, TLS, backups.
- [`spec-kit-guide.md`](guides/spec-kit-guide.md) — the spec-kit pipeline used during development (`/specify → /plan → /tasks → /implement`).

## [`reports/`](reports/) — how the system was built

- [`implementation-walkthrough.md`](reports/implementation-walkthrough.md) — step-by-step account of the build, including the errors hit along the way and how each was fixed.
- [`hardening-report.md`](reports/hardening-report.md) — post-implementation critical review: bugs found, before/after of each fix.
