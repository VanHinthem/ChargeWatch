#!/usr/bin/env bash
set -euo pipefail
# Runs only on disposable GitHub Actions runner.
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
export CW_DB_OWNER_PASSWORD=test-owner
export CW_IMPORT_PASSWORD=test-import
export CW_READ_PASSWORD=test-read
export CW_IMAGE_TAG=sha-0123456789abcdef0123456789abcdef01234567
export CW_NAS_BACKUP_DIR="$(mktemp -d)"
PROJECT="chargewatch-release-ci-$$"
dc() { docker compose -p "$PROJECT" -f deploy/nas/compose.yaml "$@"; }
cleanup() { dc down -v --remove-orphans || :; rm -rf "$CW_NAS_BACKUP_DIR"; }
trap cleanup EXIT

wait_upgrade() {
  # Compose up -d returns after START, not after this one-shot job has exited.
  container_id=$(dc ps -a -q db-upgrade)
  [ -n "$container_id" ] || { echo "db-upgrade container missing" >&2; exit 1; }
  exit_code=$(docker wait "$container_id")
  dc logs --no-color db-upgrade
  [ "$exit_code" = 0 ] || { echo "db-upgrade exited with $exit_code" >&2; exit 1; }
}
dc up -d db
dc up -d db-upgrade
wait_upgrade
log=$(dc logs --no-color db-upgrade)
printf '%s\n' "$log"
echo "$log" | grep -q 'Recorded successfully applied release'
count=$(find "$CW_NAS_BACKUP_DIR" -name '*.dump' | wc -l)
[ "$count" -eq 1 ] || { echo "Expected exactly one backup: $count" >&2; exit 1; }

# Test the UGREEN-like ordinary restart using an explicitly recreated one-shot
# container, which is stricter than a simple Docker restart.
dc up -d --no-deps --force-recreate db-upgrade
wait_upgrade
log=$(dc logs --no-color db-upgrade)
echo "$log" | grep -q 'already applied; no backup/migration required'
count=$(find "$CW_NAS_BACKUP_DIR" -name '*.dump' | wc -l)
[ "$count" -eq 1 ] || { echo "Same-tag restart created a backup" >&2; exit 1; }

# A changed release must run backup first, then migrations. This test deliberately
# only changes the maintenance image tag so the DB service itself cannot be
# unexpectedly recreated before backup.
export CW_IMAGE_TAG=sha-abcdef0123456789abcdef0123456789abcdef01
docker tag ghcr.io/vanhinthem/chargewatch-maintenance:sha-0123456789abcdef0123456789abcdef01234567 \
  ghcr.io/vanhinthem/chargewatch-maintenance:"$CW_IMAGE_TAG"
dc up -d --no-deps --force-recreate db-upgrade
wait_upgrade
log=$(dc logs --no-color db-upgrade)
echo "$log" | grep -q 'Recorded successfully applied release'
count=$(find "$CW_NAS_BACKUP_DIR" -name '*.dump' | wc -l)
[ "$count" -eq 2 ] || { echo "New tag did not create mandatory backup" >&2; exit 1; }
echo 'PostgreSQL release integration test passed'
