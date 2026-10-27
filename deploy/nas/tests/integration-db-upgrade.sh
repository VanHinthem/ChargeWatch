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
  # The UGREEN-compatible gate stays running when its release completed.
  container_id=$(dc ps -a -q db-upgrade)
  [ -n "$container_id" ] || { echo "db-upgrade container missing" >&2; exit 1; }
  for _ in $(seq 1 120); do
    status=$(docker inspect "$container_id" --format '{{.State.Status}}')
    health=$(docker inspect "$container_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')
    if [ "$status" = "running" ] && [ "$health" = "healthy" ]; then
      dc logs --no-color db-upgrade
      return 0
    fi
    if [ "$status" = "exited" ] || [ "$status" = "dead" ]; then
      dc logs --no-color db-upgrade
      echo "db-upgrade exited unexpectedly" >&2
      return 1
    fi
    sleep 1
  done
  dc logs --no-color db-upgrade
  echo "Timed out waiting for healthy db-upgrade" >&2
  return 1
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
# Full-project re-deploy: simulate the supported NAS sequence by stopping the
# entire stack before switching the immutable image tag. Verify the gate even
# when Compose creates the DB and application containers in one invocation.
dc stop
export CW_IMAGE_TAG=sha-123456789abcdef0123456789abcdef012345678
for service in db maintenance collector api; do
  docker tag "ghcr.io/vanhinthem/chargewatch-${service}:sha-0123456789abcdef0123456789abcdef01234567" \
    "ghcr.io/vanhinthem/chargewatch-${service}:${CW_IMAGE_TAG}"
done
dc up -d --no-build
wait_upgrade
log=$(dc logs --no-color db-upgrade)
echo "$log" | grep -q 'Recorded successfully applied release'
count=$(find "$CW_NAS_BACKUP_DIR" -name '*.dump' | wc -l)
[ "$count" -eq 3 ] || { echo "Full-stack redeploy failed to back up: $count" >&2; exit 1; }
docker inspect "$(dc ps -q collector)" --format '{{.State.Running}}' | grep -qx true
docker inspect "$(dc ps -q api)" --format '{{.State.Running}}' | grep -qx true
echo 'Full-stack release redeploy integration test passed'
[ "$(dc ps --services --filter status=running | wc -l)" -ge 4 ] || { echo "Expected all four services running" >&2; exit 1; }
echo 'PostgreSQL release integration test passed'
