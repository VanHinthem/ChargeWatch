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
- Backup archives must be stored on a separate host directory, with daily scheduling/retention configured externally. Archive validation is not equivalent to a full data-restore rehearsal.
- Use published version tags for all three services; do not use `latest` or `down -v`.
- Inspect resource use, backup growth and recovery on the real NAS. Historical free probabilities and uptime are **not publishable** under `docs/SPRINT3_PUBLICATION_POLICY.md`.
- **No NAS deployment without explicit user approval**, even after merging this PR.
