#!/bin/sh
# Periodic pg_dump of every service database with time-based retention.
# Runs as the entrypoint of the db-backup sidecar (see docker-compose.prod.yml).
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-86400}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
DATABASES="${DATABASES:-produto_db portador_db cartao_db}"

mkdir -p "${BACKUP_DIR}"

while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  for db in ${DATABASES}; do
    file="${BACKUP_DIR}/${db}-${stamp}.sql.gz"
    if pg_dump --no-owner --clean --if-exists "${db}" | gzip > "${file}"; then
      echo "backup ok: ${file}"
    else
      echo "backup FAILED: ${db} at ${stamp}" >&2
      rm -f "${file}"
    fi
  done
  find "${BACKUP_DIR}" -name '*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
  sleep "${BACKUP_INTERVAL_SECONDS}"
done
