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

### Separate SSD and HDD backup storage (proposed; not yet configured)

HINNAS DXP2800: Docker and PostgreSQL named volumes are on SSD `/volume2`; HDD `/volume1` is available for backups. Prefer an operator-created, restricted directory such as `/volume1/docker/chargewatch/backups` on the HDD (confirm directory and permissions first), rather than storing the only copies on SSD. Update the NAS-local `.env` to `CW_NAS_BACKUP_DIR=/volume1/docker/chargewatch/backups`. **Compose resolves bind-mount host paths when a new one-off maintenance container is created**; no database or collector restart or volume recreation is necessary. Check the path with `docker compose config` without exposing secrets; test a new backup and isolated restore from the HDD-backed archive. Only after verification, consider moving/copying older SSD-backed dumps. Do not delete the existing validated dump until the new destination has been verified. Do not run `down -v`.

Proposed retention: three daily dumps on HDD. This provides some resilience against an SSD failure but **not** loss of the entire NAS, shared security compromise or simultaneous volume failure. Retention automation remains disabled until the NAS task and HDD restore have been confirmed.
