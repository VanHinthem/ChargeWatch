#!/bin/sh
# ChargeWatch NAS release upgrade. Run from the directory containing .env and docker-compose.yaml.
# Requires an already running, healthy database and a published immutable CW_IMAGE_TAG.
set -eu
umask 077

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# The script may be copied next to the live NAS compose; resolve that directory first.
if [ -f ./docker-compose.yaml ] && [ -f ./.env ]; then
  COMPOSE_DIR=$(pwd)
  COMPOSE_FILE=docker-compose.yaml
elif [ -f ./compose.yaml ] && [ -f ./.env ]; then
  COMPOSE_DIR=$(pwd)
  COMPOSE_FILE=compose.yaml
else
  echo "ERROR: run from the NAS deployment directory containing .env and compose file" >&2
  exit 1
fi
cd "$COMPOSE_DIR"
export COMPOSE_PROJECT_NAME=chargewatch

compose() {
  docker compose --env-file .env -f "$COMPOSE_FILE" "$@"
}

echo "Preflight: validating Compose configuration and required release tag"
compose config --quiet
# Do not print .env: it contains database passwords.
release_image=$(compose config --images | grep '^ghcr.io/vanhinthem/chargewatch-api:sha-' | head -n 1)
[ -n "$release_image" ] || { echo "ERROR: API image must have an immutable sha- tag" >&2; exit 1; }
release_tag=${release_image##*:}
case "$release_tag" in
  sha-????????????????????????????????????????) ;;
  *) echo "ERROR: CW_IMAGE_TAG must be sha- followed by a full 40-character commit ID" >&2; exit 1 ;;
esac
for name in db collector maintenance api; do
  compose config --images | grep -Fx "ghcr.io/vanhinthem/chargewatch-$name:$release_tag" >/dev/null || {
    echo "ERROR: image $name is not pinned to $release_tag" >&2; exit 1;
  }
done

echo "Pulling verified release images: $release_tag"
compose pull db maintenance collector api

# A failed healthcheck or backup must stop deployment, including on the first run.
compose ps --status running db | grep -q 'chargewatch-db' || {
  echo "ERROR: DB is not running; refusing unattended upgrade" >&2; exit 1;
}
echo "Stopping collector before creating a consistent maintenance checkpoint"
compose stop collector
echo "Creating mandatory, validated PostgreSQL custom-format backup"
# No --no-deps-less run here: changing the DB image before backup would defeat the backup gate.
compose run --rm --no-deps -T maintenance /scripts/backup.sh || {
  echo "ERROR: BACKUP FAILED; upgrade cancelled. Collector is stopped for safety." >&2
  exit 1
}

echo "Updating database container after successful backup"
compose up -d --no-deps db
echo "Waiting for PostgreSQL health"
attempt=0
while :; do
  status=$(docker inspect chargewatch-db-1 --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$status" = healthy ] && break
  attempt=$((attempt+1))
  [ "$attempt" -lt 60 ] || { echo "ERROR: DB did not become healthy; collector remains stopped" >&2; exit 1; }
  sleep 2
done

echo "Applying all missing migrations; any failure blocks service rollout"
compose run --rm --no-deps -T maintenance /scripts/migrate.sh || {
  echo "ERROR: MIGRATION FAILED; do not start upgraded collector/API" >&2
  exit 1
}
echo "Deploying collector and API at $release_tag"
compose up -d --no-deps collector api
echo "Release containers:"
compose ps db collector api
echo "Upgrade complete. Check API data-status, collector checkpoint and any changed contracts."
