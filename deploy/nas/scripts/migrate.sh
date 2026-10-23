#!/bin/sh
set -eu
# Owner-only migration runner: never run concurrently with the collector.
# 001_schema.sql creates the base tables; 003_statistics.sql creates schema_migration.
# Never bootstrap a partially created database silently.
test "$(psql -X -Atqc "SELECT to_regclass('public.evse') IS NOT NULL")" = t || {
  echo "Missing base schema (evse); inspect db/init bootstrap" >&2; exit 1;
}
has_journal=$(psql -X -Atqc "SELECT to_regclass('public.schema_migration') IS NOT NULL")
if [ "$has_journal" != t ]; then
  echo "Migration journal missing: applying 003_statistics first (creates journal)."
  psql -X -v ON_ERROR_STOP=1 -1 -f /migrations/003_statistics.sql
fi
found=0
for file in /migrations/[0-9][0-9][0-9]_*.sql; do
  [ -f "$file" ] || continue
  found=1
  version=${file##*/}
  version=${version%.sql}
  applied=$(printf '%s\n' "SELECT EXISTS(SELECT 1 FROM schema_migration WHERE version=:'version');" | psql -X -At -v ON_ERROR_STOP=1 -v version="$version")
  if [ "$applied" = t ]; then
    echo "SKIP $version (already applied)"
    continue
  fi
  echo "APPLY $version"
  # Single transaction for each SQL file; SQL errors abort the entire migration.
  psql -X -v ON_ERROR_STOP=1 -1 -f "$file"
  applied=$(printf '%s\n' "SELECT EXISTS(SELECT 1 FROM schema_migration WHERE version=:'version');" | psql -X -At -v ON_ERROR_STOP=1 -v version="$version")
  [ "$applied" = t ] || { echo "Migration $version did not record its version; stop" >&2; exit 1; }
done
[ "$found" = 1 ] || { echo "No migrations found" >&2; exit 1; }
echo "Migration pass completed."
