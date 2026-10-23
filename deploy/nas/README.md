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
4. Check the planned migration/bootstrap order against the live database before starting the collector. The initial `db/init` scripts run only when the data volume is empty. Apply all repository SQL migrations **in numerical order, exactly once as required by each script**, using the database owner. Do not point these commands at an existing unrelated database. A formal idempotent upgrade runner and tested restore are acceptance criteria before unattended operation.

Commands (from repository root; inspect output before applying):
```bash
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml config
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml build collector
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml up -d db
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml ps
# Inspect db/migrations/*.sql, then apply required migrations with the DB owner.
# Start collection ONLY after the schema and grants are confirmed:
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml up -d collector
docker compose --env-file deploy/nas/.env -f deploy/nas/compose.yaml logs --tail=100 collector
```

## Verify and maintain
- Check successful full Locations + Tariffs import in collector logs, not just a healthy `db` container.
- Verify `import_run`/snapshot provenance and that the successful import timestamp advances on real feed changes. HTTP304 may be entirely normal.
- Check logs and stale-data warnings after NAS reboot and network interruptions. Restart must reuse the same `pgdata` and `ndw-cache` volumes.
- Establish and test a regular `pg_dump` backup to **a second NAS location** or separate backup system; keep backup outside the Compose volumes and verify restoration into an isolated database.
- Inspect actual disk growth and data-source freshness for several days before calling collection reliable. Candidate poll-hour coverage is NOT a publishable historical availability probability.

## Boundaries and outstanding checks
This PR contains infrastructure preparation only. **No NAS commands have been run and no production credentials or data have been accessed.** Before deployment we still need user approval, migration/restore rehearsal, image build, NAS resource sizing, and chosen volume/backup locations. Never deploy solely because a PR was merged.
