# NAS deployment — prebuilt images (no Git checkout on NAS)

**Do not use `git clone` or `docker compose build` on the NAS.** GitHub Actions builds three linux/amd64 GHCR images at an identical immutable commit tag:

- `ghcr.io/vanhinthem/chargewatch-db:sha-<main-commit>`: PostGIS 18 + bundled initial schema and DB role creation.
- `ghcr.io/vanhinthem/chargewatch-maintenance:sha-<main-commit>`: PostgreSQL 18 client + SQL migrations + backup scripts.
- `ghcr.io/vanhinthem/chargewatch-collector:sha-<main-commit>`: .NET 10 NDW collector.

Workflow: `.github/workflows/nas-images.yml`, running on relevant main changes and manually through GitHub Actions. Use **only tags actually published successfully by the workflow**. Never substitute a PR-branch SHA or assume publication succeeded. For private GHCR images, authorize once using `docker login ghcr.io` with a read:packages token, not a token in Compose.

## NAS files

Only these local configuration files need to be copied to the NAS (e.g. via SCP from Windows, not Git on the NAS):

- `/volume2/docker/chargewatch/app/compose.yaml` — copy `deploy/nas/compose.yaml`
- `/volume2/docker/chargewatch/app/.env` — create locally from `deploy/nas/.env.example`, set distinct strong DB owner/import/reader passwords and `CW_IMAGE_TAG=sha-<published-main-SHA>`; protect file permissions/ACL.
- `CW_NAS_BACKUP_DIR=/volume2/docker/chargewatch/backups` — pre-existing restricted directory outside Docker volumes.

The images include everything required for initialization and migrations; **no `db/init`, SQL scripts, or repository checkout are required on the NAS**. Named volumes retain DB data and NDW cache across upgrades. No database ports are exposed. Adjust NAS backup ACLs/ownership carefully; never recursively change permissions on other services.

From `/volume2/docker/chargewatch/app`:

```sh
docker compose --env-file .env -f compose.yaml config --quiet
docker compose --env-file .env -f compose.yaml pull
docker compose --env-file .env -f compose.yaml up -d db
docker compose --env-file .env -f compose.yaml run --rm maintenance /scripts/migrate.sh
docker compose --env-file .env -f compose.yaml run --rm maintenance /scripts/backup.sh
# Verify restore to a separate DB; then, with explicit operator approval:
docker compose --env-file .env -f compose.yaml up -d collector
docker compose --env-file .env -f compose.yaml logs --tail=100 collector
```

The maintenance container's entrypoint is `/bin/sh`. To restore: `docker compose ... run --rm -e PGDATABASE=<isolated-test-db> maintenance -c 'pg_restore --exit-on-error --no-owner --no-acl -d <isolated-test-db> /backups/<file>.dump'`. Create the isolated test database first; **never target the live collector database**.

## Operational boundaries

- Initial `db/init` executes **only on a fresh DB volume**, existing databases are not reinitialized when updating the image.
- Run migrations before starting/upgrading a collector; stop a running collector for migration maintenance.
- Backup archives must be stored on a separate host directory; configure the daily NAS task explicitly as described below, and defer retention until an isolated restore has been tested. Archive validation is not equivalent to a full data-restore rehearsal.
- Use published version tags for all three services; do not use `latest` or `down -v`.
- Inspect resource use, backup growth and recovery on the real NAS. Historical free probabilities and uptime are **not publishable** under `docs/SPRINT3_PUBLICATION_POLICY.md`.
- Any new deployment or upgrade still requires explicit operator approval; the initial NAS deployment was authorized and carried out on 23 October 2026.

## First live NAS deployment — operational handover (23 October 2026)

Confirmed by operator on HINNAS using published images tagged `sha-9dc9964cc4339003bfd1f33bd39864c17615bc54`: Docker pulled all three images; PostgreSQL 18.6 healthy; migrations `003_statistics` through `007_poll_hour_coverage` applied; `chargewatch_pgdata` and `chargewatch_ndw-cache` persisted; NDW collector up. After an intentionally interrupted initial import, a later transaction completed: **80,838 charging locations, 203,366 EVSEs, 1 completed import**. Staging log reported 204,069 connectors and 101,368 tariffs, but their persisted counts have not yet been independently checked. No schema/data reset occurred.

The **pre-import** archive `/backups/chargewatch-20261023T153609Z.dump` was created and its archive index validated. This is **not** yet a confirmed post-import archive or NAS restore rehearsal.

### Post-import backup and verification (operator on NAS)

```sh
cd /volume2/docker/chargewatch/app
sudo docker compose run --rm maintenance /scripts/backup.sh
# Confirm file, nonzero size and permissions in configured CW_NAS_BACKUP_DIR.
sudo ls -lh /volume2/docker/chargewatch/backups/chargewatch-*.dump
```

A successful `pg_restore --list` (already performed by the script) confirms that the archive is readable, **not** that a complete restoration succeeds. To rehearse recovery, create a **separate database** (never drop or overwrite `chargewatch`) and restore the newest archive into it using the maintenance image. Compare table counts and schema-migration rows; clean up only that isolated test database after explicit verification. Do not automate retention before this restore test is confirmed.

### Daily backups in NAS Task Scheduler (manual NAS configuration; not yet activated)

Configure a **daily** scheduled task under the NAS Task Scheduler, running under an account allowed to execute Docker, with this command:

```sh
cd /volume2/docker/chargewatch/app && /usr/local/bin/docker compose run --rm -T maintenance /scripts/backup.sh
```

Verify Docker binary location first with `command -v docker`; replace `/usr/local/bin/docker` if the result differs. Prefer a suitably permissioned service account; never embed database passwords in the scheduled task. The secrets remain in the restricted local `.env`. Set a fixed quiet-hour schedule in the NAS UI and inspect task history after its first run. Test the exact command manually under the *same user* the scheduler will use. The script uses UTC timestamps and permissions `umask 077`. **Do not register a duplicate scheduled backup task**. A scheduler task configured in the NAS UI is an operator action, not something a GitHub merge can remotely enable.

**Retention policy proposal:** 3 daily archives, to be enabled *only after* the isolated restore test, folder-permissions review, and successful first scheduled execution. Until then, preserve every archive; check disk use manually and do not add an unchecked `find -delete` command. Periodically repeat isolated restore rehearsals. Monitor `docker compose ps`, collector logs, last `import_run` status, backup task history and disk usage. PostgreSQL/NDW source volumes must never be removed with `down -v`.

### Dual-volume backup policy (proposed; not yet configured)

Operator chooses **one daily database export** on SSD `/volume2/docker/chargewatch/backups`, then a verified copy of that exact dump to HDD `/volume1/chargewatch/backups`. Keep three daily archives on **each** volume once the end-to-end task and restore are verified. Avoid two independent `pg_dump` calls (different snapshots). Existing Compose `CW_NAS_BACKUP_DIR` remains on volume 2; no DB/collector restart is necessary.

On the NAS, create the volume 1 directory with appropriate restricted permissions, then test copying an existing *post-import* dump using `cp -p` and check equality with `sha256sum` on both files. Do not erase either copy. For automation, run the existing backup script once, identify its newly created file **from that run**, copy to HDD with a temporary filename, verify checksum, then atomically rename into place. Treat checksum mismatch, failed copy or insufficient free space as **task failure**, not success. Do not use a naive wildcard that could silently choose an older backup.

Once the scheduled task is proven reliable, enable retention of the most recent **three distinct daily archives on each volume** only after both the new daily dump and its mirror are validated; do not delete the last good archive on failure. Test restoring from an HDD copy to a separate database before declaring the second destination protected. Both volumes share the same physical NAS, so this is not an off-device backup.

### Dual-volume backup script and NAS Task Scheduler

Script tracked in Git: `deploy/nas/scripts/backup-dual-volume.sh`. The script is **host-side**, not included in the GHCR maintenance image. Copy only this file to `/volume2/docker/chargewatch/app/backup-dual-volume.sh` (e.g. SCP from Windows after merge; no Git checkout on NAS). Run via `sudo sh /volume2/docker/chargewatch/app/backup-dual-volume.sh`. It executes the existing maintenance-image `/scripts/backup.sh` **once**, extracts the validated archive filename, copies it to the HDD via a temporary file, verifies byte identity and SHA-256, and atomically publishes the HDD copy. A directory lock prevents overlap. Unpaired existing backups are preserved.

**Initial testing: automatic retention is disabled by default.** After validating a manual run and separately rehearsing a restore from the HDD copy, use `CW_ENABLE_RETENTION=1` to opt into three paired archived backups per volume (`CW_BACKUP_KEEP=3`). Never use retention to solve urgent disk pressure before understanding backups. The script fails if either target is missing/unwritable, if Docker/backup fails or if mirror verification fails.

On the NAS, manually confirm `command -v docker` and test `sudo sh /volume2/docker/chargewatch/app/backup-dual-volume.sh`. The script expects `/volume2/docker/chargewatch/.env` to keep `CW_NAS_BACKUP_DIR=/volume2/docker/chargewatch/backups`; it does not alter or restart running DB/collector services. Restrict NAS backup folder and script permissions/ACL. Only after the manual test is confirmed, configure one daily NAS Task Scheduler command `/bin/sh /volume2/docker/chargewatch/app/backup-dual-volume.sh` under a Docker-authorized account; test the same command under the scheduler identity and review task history. Do not schedule both this script and the earlier single-volume backup command.

**Actual HINNAS layout confirmed:** Compose project and secrets are stored in `/volume2/docker/chargewatch/docker-compose.yaml` and `/volume2/docker/chargewatch/.env`; script may live in `/volume2/docker/chargewatch/app`. The script resolves Compose from the parent directory by default, preferring `docker-compose.yaml`, and explicitly passes `--env-file` and `-f`. Previous `Missing compose.yaml or .env` validation failure was caused by the old incorrect assumption. Re-download updated script before retrying.

## Internal API-only NAS deployment

The API has a separate image `ghcr.io/vanhinthem/chargewatch-api:sha-<merged-main-SHA>`.
Set `CW_API_IMAGE_TAG` independently of `CW_IMAGE_TAG`: never upgrade the existing collector or DB as part of this smoke test.
The Compose API publishes `127.0.0.1:5088:8080`, **loopback on the NAS only**. No internet/LAN access, reverse proxy or router forwarding.
The API uses the existing `chargewatch_read` password via server-side environment; never place passwords in chat or GitHub.

Only after the PR is approved/merged and the NAS Images workflow has successfully **published** the API image:

1. Copy `deploy/nas/compose.yaml` from merged main to the actual NAS file `/volume2/docker/chargewatch/docker-compose.yaml` (back up the existing file first); preserve `.env` and Docker volumes.
2. Add `CW_API_IMAGE_TAG=sha-<verified-published-main-SHA>` to `/volume2/docker/chargewatch/.env`. Leave `CW_IMAGE_TAG` untouched. Optionally set `CW_API_STALE_AFTER_MINUTES=15`.
3. Run these commands via SSH on NAS from `/volume2/docker/chargewatch` (prefix `sudo` when appropriate):

```sh
docker compose --env-file .env -f docker-compose.yaml config --quiet
docker compose --env-file .env -f docker-compose.yaml pull api
docker compose --env-file .env -f docker-compose.yaml up -d --no-deps api
docker compose --env-file .env -f docker-compose.yaml ps api
docker compose --env-file .env -f docker-compose.yaml logs --tail=100 api
curl -i --max-time 20 http://127.0.0.1:5088/api/v1/data-status
curl -i --max-time 20 'http://127.0.0.1:5088/api/v1/locations?limit=2'
```

Use the numerical `id` from the location search result to smoke-test both detail routes:

```sh
curl -i --max-time 20 http://127.0.0.1:5088/api/v1/locations/REPLACE_ID
curl -i --max-time 20 http://127.0.0.1:5088/api/v1/locations/REPLACE_ID/statistics
```

Expected: HTTP 200 on data-status and search, no DB role error in logs, at least some known locations, nested EVSE/connector details, and statistics shape (profiles may still be immature). Compare reported freshness to collector observations before trusting stale indicators.
HTTP is acceptable **only via the NAS loopback** for this phase. For workstation testing, tunnel using SSH (`ssh -L 5088:127.0.0.1:5088 NAS_USER@NAS_HOST`); do not publish HTTP to the LAN/internet. HTTPS-redirection in ASP.NET is not a replacement for TLS termination. A public phase must configure HTTPS and trusted proxies independently.

Rollback only the API, leaving DB and collector intact:

```sh
docker compose --env-file .env -f docker-compose.yaml stop api
```

Never use `down -v`, reset the DB, run migrations, or change `CW_IMAGE_TAG` for this test.
