#!/bin/sh
# Apply pending numbered SQL migrations, each atomically and under an advisory lock.
set -eu
: "${PGHOST:?PGHOST required}"
: "${PGDATABASE:?PGDATABASE required}"
: "${PGUSER:?PGUSER required}"
: "${PGPASSWORD:?PGPASSWORD required}"
export PGOPTIONS="-c statement_timeout=0"
migration_dir="${CW_MIGRATIONS_DIR:-/migrations}"
set -- "$migration_dir"/[0-9][0-9][0-9]_*.sql
[ -f "$1" ] || { echo "No numbered SQL migrations found" >&2; exit 1; }
for file do
  base="${file##*/}"
  version="${base%.sql}"
  echo "Checking migration: $version"
  # One psql connection holds a transaction-scoped advisory lock through the
  # version check and script execution. A failed script rolls back everything.
  {
    printf '%s\n' 'BEGIN;' 'SELECT pg_advisory_xact_lock(752567001);'
    printf "SELECT CASE WHEN EXISTS (SELECT 1 FROM schema_migration WHERE version = '%s') THEN 'false' ELSE 'true' END AS apply_migration \\gset\n" "$version"
    printf '%s\n' '\if :apply_migration'
    printf '\echo Applying %s\n' "$version"
    printf '\i %s\n' "$file"
    printf '%s\n' '\else'
    printf '\echo Already applied\n' '\endif' 'COMMIT;'
  } | psql -X -v ON_ERROR_STOP=1 -q
done
echo "Database migrations checked successfully."
