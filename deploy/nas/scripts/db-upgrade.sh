#!/bin/sh
# Idempotent release checkpoint for an operator-approved UGREEN Compose deployment.
set -eu
umask 077
: "${CW_IMAGE_TAG:?CW_IMAGE_TAG required}"
case "$CW_IMAGE_TAG" in
  sha-????????????????????????????????????????) ;;
  *) echo "CW_IMAGE_TAG must be an immutable sha-<40 hex> release" >&2; exit 1 ;;
esac
# Fail closed when the previous collector still has database sessions.
# When upgrading via UGREEN, stop the whole Compose project first.
# This guard complements (but cannot replace) a production-tested stop/start sequence.
assert_collector_stopped() {
  connected=$(psql -X -At -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) FROM pg_stat_activity WHERE usename = 'chargewatch_import' AND pid <> pg_backend_pid()")
  if [ "$connected" -ne 0 ]; then
    echo "ERROR: $connected collector/import database connection(s) exist; stop the UGREEN project before updating" >&2
    exit 1
  fi
}
assert_collector_stopped
psql -X -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE TABLE IF NOT EXISTS chargewatch_release_checkpoint (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    image_tag text NOT NULL,
    upgraded_at timestamptz NOT NULL DEFAULT now()
);
SQL
applied=$(psql -X -At -v ON_ERROR_STOP=1 -c 'SELECT image_tag FROM chargewatch_release_checkpoint WHERE singleton = true')
if [ "$applied" = "$CW_IMAGE_TAG" ]; then
  echo "Release $CW_IMAGE_TAG already applied; no backup/migration required."
  exit 0
fi
echo "Database release change: ${applied:-unrecorded} -> $CW_IMAGE_TAG"
assert_collector_stopped
echo "Creating mandatory pre-migration backup..."
/scripts/backup.sh
assert_collector_stopped
echo "Applying pending migrations..."
/scripts/migrate.sh
# Called only after a successful migration pass; errors must never mark release complete.
psql -X -v ON_ERROR_STOP=1 -v new_tag="$CW_IMAGE_TAG" <<'SQL'
INSERT INTO chargewatch_release_checkpoint(singleton, image_tag, upgraded_at)
VALUES (true, :'new_tag', now())
ON CONFLICT (singleton) DO UPDATE
  SET image_tag = EXCLUDED.image_tag,
      upgraded_at = EXCLUDED.upgraded_at;
SQL
echo "Recorded successfully applied release $CW_IMAGE_TAG"
