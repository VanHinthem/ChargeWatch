#!/usr/bin/env bash
# Test release decision tree without touching a real database or NAS backup directory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT
mkdir -p "$TEST_TMP/bin"
export TEST_STATE="$TEST_TMP/state"
export TEST_LOG="$TEST_TMP/log"
export CW_IMAGE_TAG="sha-0123456789abcdef0123456789abcdef01234567"
cat > "$TEST_TMP/bin/psql" <<'PSQL'
#!/bin/sh
set -eu
statement="$*"
case "$statement" in
  *pg_stat_activity*) cat "$TEST_STATE/connections"; exit 0 ;;
  *to_regclass*) [ -f "$TEST_STATE/applied" ] && echo t || echo f; exit 0 ;;
  *'SELECT image_tag'*) cat "$TEST_STATE/applied"; exit 0 ;;
esac
sql=$(cat)
case "$sql" in
  *'INSERT INTO chargewatch_release_checkpoint'*)
    if [ -f "$TEST_STATE/commit_fails" ]; then exit 2; fi
    printf '%s' "$CW_IMAGE_TAG" > "$TEST_STATE/applied"
    echo commit >> "$TEST_LOG"
    ;;
  *'CREATE TABLE IF NOT EXISTS chargewatch_release_checkpoint'*)
    echo checkpoint-schema >> "$TEST_LOG" ;;
  *) echo "unexpected psql call: $statement $sql" >&2; exit 3 ;;
esac
PSQL
chmod +x "$TEST_TMP/bin/psql"
export PATH="$TEST_TMP/bin:$PATH"
# Hardwired /scripts is intentional in the shipped image; use temporary
# root on Linux runner and clean it up when the test concludes.
[ ! -e /scripts ] || { echo "Refusing test: /scripts already exists" >&2; exit 1; }
mkdir /scripts
cleanup_scripts() { rm -rf /scripts; rm -rf "$TEST_TMP"; }
trap cleanup_scripts EXIT
cp "$ROOT/deploy/nas/scripts/db-upgrade.sh" /scripts/db-upgrade.sh
cat >/scripts/backup.sh <<'BACKUP'
#!/bin/sh
echo backup >> "$TEST_LOG"
[ ! -f "$TEST_STATE/backup_fails" ]
BACKUP
cat >/scripts/migrate.sh <<'MIGRATE'
#!/bin/sh
echo migrate >> "$TEST_LOG"
[ ! -f "$TEST_STATE/migration_fails" ]
MIGRATE
chmod +x /scripts/*.sh

run() { sh /scripts/db-upgrade.sh; }
assert_log() {
  actual=$(cat "$TEST_LOG" 2>/dev/null || true)
  [ "$actual" = "$1" ] || { echo "Expected [$1], actual [$actual]" >&2; exit 1; }
}
mkdir -p "$TEST_STATE"
echo 0 > "$TEST_STATE/connections"
: > "$TEST_LOG"
run
assert_log "$(printf 'backup\nmigrate\ncheckpoint-schema\ncommit')"
# Same release: no backup, no migrate, and can already have importer connections.
echo 2 > "$TEST_STATE/connections"
: > "$TEST_LOG"
run
assert_log ""
# Changed release: fail closed when collector is still connected.
export CW_IMAGE_TAG="sha-abcdef0123456789abcdef0123456789abcdef01"
if run; then echo "Expected collector guard failure" >&2; exit 1; fi
assert_log ""
echo 0 > "$TEST_STATE/connections"
touch "$TEST_STATE/backup_fails"
if run; then echo "Expected backup failure" >&2; exit 1; fi
assert_log "backup"
[ "$(cat "$TEST_STATE/applied")" != "$CW_IMAGE_TAG" ]
rm "$TEST_STATE/backup_fails"
: > "$TEST_LOG"
touch "$TEST_STATE/migration_fails"
if run; then echo "Expected migration failure" >&2; exit 1; fi
assert_log "$(printf 'backup\nmigrate')"
[ "$(cat "$TEST_STATE/applied")" != "$CW_IMAGE_TAG" ]
rm "$TEST_STATE/migration_fails"
: > "$TEST_LOG"
run
assert_log "$(printf 'backup\nmigrate\ncheckpoint-schema\ncommit')"
[ "$(cat "$TEST_STATE/applied")" = "$CW_IMAGE_TAG" ]
echo "NAS release upgrade unit tests passed."
