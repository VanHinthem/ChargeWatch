# Issue #65 — OCPI source preservation and backfill

## Design / fields
- Nullable `source_data jsonb` in `charging_location`, `evse` and `connector` (migration 016).
- Location JSON removes only the nested `evses` property; EVSE JSON removes only nested `connectors`; connector JSON is retained in full.
- Other OCPI fields, including nested metadata that belong to the object, remain intact. Existing `tariff.details jsonb` remains unchanged.
- The regular typed columns remain authoritative for operational filtering, map queries and API contracts. No new GIN indexes on the JSON blobs.
- Original IDs and historical rows are preserved. The original JSON is not exposed to Android.

## Import semantics
- `source_data` is upserted in the same transaction as structured fields; `IS DISTINCT FROM` avoids writes when payload unchanged.
- Existing rows start as `NULL`, until a controlled full reimport. The watch runner does not automatically backfill on an HTTP 304; forcing reimport needs an explicit safe operation without incorrectly claiming a new verified 200 Locations snapshot.
- This change does not attempt to add extra typed electrical fields; issue #64 will handle connector fields and safe AC derivation.

## Review and tests before merge
1. Build importer .NET 10.
2. Apply migration 016 only in isolated local test DB (ensure migration 015 already applied).
3. Run local `--bulk` using current NDW files; ensure `source_data` coverage matches imported active locations/EVSEs/connectors.
4. Verify for selected objects: `source_data` retains original known keys; locations lack nested `evses`; EVSE lacks nested `connectors`; connector retains `format`, `max_voltage`, `max_amperage` when source provides them.
5. Compare `pg_total_relation_size`, importer runtime, WAL and sample viewport query planning to baseline. No current production deployment.
6. Test idempotent repeated bulk import and malformed import rollback. Keep unrelated historical data stable.

## Note
The source JSONB makes a *snapshot of currently imported data*, not a historical version log. No automatic backfill of locations already absent from NDW.
