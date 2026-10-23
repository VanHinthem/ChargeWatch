#!/bin/sh
# ChargeWatch: one consistent database dump to SSD, verified mirror to HDD.
# Run from NAS Task Scheduler as a user with Docker privileges.
set -eu
umask 077

APP_DIR=${CW_APP_DIR:-/volume2/docker/chargewatch}
SSD_DIR=${CW_SSD_BACKUP_DIR:-/volume2/docker/chargewatch/backups}
HDD_DIR=${CW_HDD_BACKUP_DIR:-/volume1/chargewatch/backups}
KEEP=${CW_BACKUP_KEEP:-3}
ENABLE_RETENTION=${CW_ENABLE_RETENTION:-0}
DOCKER=${CW_DOCKER:-docker}

case "$KEEP" in *[!0-9]*|'') echo "CW_BACKUP_KEEP must be an integer >= 2" >&2; exit 2;; esac
[ "$KEEP" -ge 2 ] || { echo "CW_BACKUP_KEEP must be >= 2" >&2; exit 2; }
[ "$SSD_DIR" != "$HDD_DIR" ] || { echo "SSD/HDD backup paths must differ" >&2; exit 2; }
for dir in "$APP_DIR" "$SSD_DIR" "$HDD_DIR"; do
  [ -d "$dir" ] || { echo "Missing directory: $dir" >&2; exit 1; }
done
[ -f "$APP_DIR/.env" ] || { echo "Missing .env in $APP_DIR" >&2; exit 1; }
if [ -f "$APP_DIR/docker-compose.yaml" ]; then
  COMPOSE_FILE="$APP_DIR/docker-compose.yaml"
elif [ -f "$APP_DIR/docker-compose.yml" ]; then
  COMPOSE_FILE="$APP_DIR/docker-compose.yml"
elif [ -f "$APP_DIR/compose.yaml" ]; then
  COMPOSE_FILE="$APP_DIR/compose.yaml"
else
  echo "Missing docker-compose.yaml, docker-compose.yml or compose.yaml in $APP_DIR" >&2
  exit 1
fi
[ -w "$SSD_DIR" ] && [ -w "$HDD_DIR" ] || { echo "Backup path is not writable" >&2; exit 1; }
command -v "$DOCKER" >/dev/null || { echo "Docker CLI unavailable: $DOCKER" >&2; exit 1; }
command -v sha256sum >/dev/null && command -v cmp >/dev/null || { echo "Required checksum tools unavailable" >&2; exit 1; }

lock="$APP_DIR/.backup-dual-volume.lock"
if ! mkdir "$lock" 2>/dev/null; then
  echo "Backup lock exists ($lock). Check for a running task; remove stale lock manually only when safe." >&2
  exit 1
fi
tmp=''
cleanup() {
  if [ -n "$tmp" ]; then rm -f -- "$tmp"; fi
  rmdir "$lock"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$APP_DIR"
echo "Running one PostgreSQL backup in $SSD_DIR"
# Capture backup.sh output; preserve failure exit status before parsing file name.
log=$("$DOCKER" compose --env-file "$APP_DIR/.env" -f "$COMPOSE_FILE" run --rm -T maintenance /scripts/backup.sh) || {
  echo "PostgreSQL dump/validation failed; mirror and retention skipped" >&2
  exit 1
}
printf '%s\n' "$log"

# Reject unexpected output and never mirror a pre-existing, guessed or partial file.
basename=$(printf '%s\n' "$log" | sed -n 's|^Backup archive structure validated: /backups/\(chargewatch-[0-9]*T[0-9]*Z\.dump\)$|\1|p')
case "$basename" in
  chargewatch-????????T??????Z.dump) ;;
  *) echo "Validated backup filename missing/invalid; mirror and retention skipped" >&2; exit 1 ;;
esac
src="$SSD_DIR/$basename"
dst="$HDD_DIR/$basename"
[ -s "$src" ] || { echo "Validated SSD archive missing/empty: $src" >&2; exit 1; }
[ ! -e "$dst" ] || { echo "HDD target already exists: $dst; not overwriting" >&2; exit 1; }

tmp="$HDD_DIR/.$basename.tmp.$$"
cp -- "$src" "$tmp"
cmp -s -- "$src" "$tmp" || { echo "SSD/HDD mirror mismatch; retention skipped" >&2; exit 1; }
src_hash=$(sha256sum "$src" | cut -d ' ' -f1)
dst_hash=$(sha256sum "$tmp" | cut -d ' ' -f1)
[ "$src_hash" = "$dst_hash" ] || { echo "SHA-256 mismatch; retention skipped" >&2; exit 1; }
mv -- "$tmp" "$dst"
tmp=''
echo "Mirrored and verified: $basename SHA256=$src_hash"

# Retention is opt-in after manual validation and an isolated HDD restore.
if [ "$ENABLE_RETENTION" != "1" ]; then
  echo "Retention disabled (CW_ENABLE_RETENTION=1 to enable after review)"
  echo "Dual-volume ChargeWatch backup completed"
  exit 0
fi
# Retention only after BOTH copies of today's archive have been verified.
# Keep N newest identically named, mirrored daily archives; never delete unmatched
# files (e.g. pre-existing SSD-only backups) automatically.
# Use shell glob order (ISO-like filenames) rather than parsing ls output.
set -- "$SSD_DIR"/chargewatch-????????T??????Z.dump
[ -f "$1" ] || { echo "No archives found; retention skipped"; exit 0; }
for candidate do
  [ -f "$candidate" ] || continue
  file=${candidate##*/}
  [ -f "$HDD_DIR/$file" ] || continue
  # Count newer paired archives; remove only if at least KEEP newer pairs exist.
  newer=0
  for peer in "$SSD_DIR"/chargewatch-????????T??????Z.dump; do
    [ -f "$peer" ] || continue
    peerfile=${peer##*/}
    if [ "$peerfile" \> "$file" ] && [ -f "$HDD_DIR/$peerfile" ]; then
      newer=$((newer+1))
    fi
  done
  if [ "$newer" -ge "$KEEP" ]; then
    # Before deletion, both files must be identical.
    if cmp -s -- "$candidate" "$HDD_DIR/$file"; then
      rm -- "$candidate" "$HDD_DIR/$file"
      echo "Retention removed verified older pair: $file"
    else
      echo "WARNING: older pair differs; preserving $file" >&2
    fi
  fi
done
echo "Dual-volume ChargeWatch backup completed"
