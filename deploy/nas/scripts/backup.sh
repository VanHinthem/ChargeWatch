#!/bin/sh
set -eu
# /backups must be a user-chosen NAS directory outside database/cache volumes.
umask 077
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
destination="/backups/chargewatch-$timestamp.dump"
[ -d /backups ] && [ -w /backups ] || { echo "Backup directory unavailable/unwritable" >&2; exit 1; }
echo "Creating backup $destination"
pg_dump --format=custom --no-owner --no-acl --file="$destination"
pg_restore --list "$destination" >/dev/null
echo "Backup archive structure validated: $destination"
echo "NOTE: verify an actual restore to an ISOLATED database before relying on backups."
