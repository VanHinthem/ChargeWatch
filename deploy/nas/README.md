# Sprint 4 — NAS Data Collector (candidate, not deployed)

Goal: collect real NDW history continuously on the NAS, independent from Windows DEV, **before** building the API or Android app. This environment is data collection only, not a public service. No host ports are published.

## Design
- `db`: PostGIS 18, database `chargewatch`, named volume `pgdata`. Its entrypoint initialization scripts are the same `db/init` scripts used by local Compose and **run only for an empty database volume**.
- `collector`: .NET 10 importer with existing `--watch` loop: Locations checked every 5 minutes, Tariffs every 60 minutes. On unchanged HTTP responses there is no new import. Named volume `ndw-cache` contains downloaded files and HTTP refresh state. No second scheduler or new application schema.
- Independent restricted database writer `chargewatch_import`; PostgreSQL is on the internal Compose network, not published through the NAS. Secrets belong only in the untracked `deploy/nas/.env`.
- No automatic `docker compose down -v`, destructive rebuild or automatic schema drop. Existing NDW quality and publication limits still apply.

## Prepare on a test/collection-only NAS path
1. Use a dedicated checkout of `main` after this PR is approved and merged. Confirm NAS CPU supports the chosen .NET/PostGIS images, available disk and Docker Compose v2.
2. Copy `deploy/nas/.env.example` to `deploy/nas/.env`; replace all three `CHANGE_ME` values with distinct strong credentials. Restrict the file's permissions. **Never add credentials to GitHub.**
3. Review the PostgreSQL volume path and backup destination. Named volumes persist container replacement, but are **not a backup**.
4. Check the planned migration/bootstrap order against the live database before starting the collector. The initial `db/init` scripts run only when the data volume is empty. Use the included owner-only `maintenance` service to apply SQL files in filename order, skipping versions already in `schema_migration`. On a fresh base schema, `003_statistics.sql` creates `schema_migration` before the remaining migrations. The script stops if even the base `evse` table is missing. Never point these commands at an unrelated database. **Rehearse restore into an isolated database before NAS deployment.**

Commands (from repository root; inspect output before applying):
```bash
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml config
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml build collector
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml up -d db
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml ps
# Inspect db/migrations/*.sql, ensure fresh schema/init success, then apply with the owner:
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml run --rm maintenance /bin/sh /scripts/migrate.sh
# Use an EXISTING private backup directory in CW_NAS_BACKUP_DIR, outside Docker volumes:
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml run --rm maintenance /bin/sh /scripts/backup.sh
# Start collection ONLY after schema, DB roles, backup and restore rehearsal are confirmed:
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml up -d collector
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml logs --tail=100 collector
```

## Verify and maintain
- Check successful full Locations + Tariffs import in collector logs, not just a healthy `db` container.
- Verify `import_run`/snapshot provenance and that the successful import timestamp advances on real feed changes. HTTP304 may be entirely normal.
- Check logs and stale-data warnings after NAS reboot and network interruptions. Restart must reuse the same `pgdata` and `ndw-cache` volumes.
- Establish and schedule regular `maintenance /scripts/backup.sh` runs to **a separate NAS backup directory** or external backup system; the command creates a `pg_dump -Fc` file and validates its archive TOC. **This does not prove restore success:** independently restore into an isolated database and verify schema, row counts and permissions. Do not test restore against the live collector DB.
- Inspect actual disk growth and data-source freshness for several days before calling collection reliable. Candidate poll-hour coverage is NOT a publishable historical availability probability.

## Boundaries and outstanding checks
The maintenance service is only active when explicitly invoked via `docker compose run --rm maintenance ...`, not as a permanent container. The `CW_NAS_BACKUP_DIR` must point to an existing, writable host directory, and its permissions/retention are the operator's responsibility. This PR contains infrastructure preparation only. **No NAS commands have been run and no production credentials or data have been accessed.** Before deployment we still need user approval, migration/restore rehearsal, image build, NAS resource sizing, and chosen volume/backup locations. Never deploy solely because a PR was merged.
