#!/bin/sh
set -eu
# Owner-only migration runner: never run concurrently with the collector.
# Init scripts must have created schema_migration in the empty DB first.
test "$(psql -X -Atqc "SELECT to_regclass('public.schema_migration') IS NOT NULL")" = t || {
  echo "Missing schema_migration; inspect db/init bootstrap before proceeding" >&2; exit 1;
}
found=0
for file in /migrations/[0-9][0-9][0-9]_*.sql; do
  [ -f "$file" ] || continue
  found=1
  version=${file##*/}
  version=${version%.sql}
  applied=$(psql -X -At -v version="$version" -c "SELECT EXISTS(SELECT 1 FROM schema_migration WHERE version=:'version')")
  if [ "$applied" = t ]; then
    echo "SKIP $version (already applied)"
    continue
  fi
  echo "APPLY $version"
  # Single transaction for each SQL file; SQL errors abort the entire migration.
  psql -X -v ON_ERROR_STOP=1 -1 -f "$file"
  applied=$(psql -X -At -v version="$version" -c "SELECT EXISTS(SELECT 1 FROM schema_migration WHERE version=:'version')")
  [ "$applied" = t ] || { echo "Migration $version did not record its version; stop" >&2; exit 1; }
done
[ "$found" = 1 ] || { echo "No migrations found" >&2; exit 1; }
echo "Migration pass completed."
