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

## Local integration test — 27 October 2026

Run using isolated `chargewatch-s4test` PostgreSQL 18/PostGIS and local importer image; NAS production untouched.

- Importer .NET build and Docker build succeeded. Migration 016 completed: 3 ALTER TABLE, 1 schema migration insert.
- Initial `--bulk` invocation omitted required file arguments; rejected by CLI before import. The first correctly invoked bulk import failed on SQL operator ambiguity `payload->'data' - 'evses'` and rolled back. Fixed by parentheses around extraction, also for EVSE JSON.
- Successful bulk import: 80,925 locations, 203,509 EVSEs, 204,211 connectors, 102,024 tariffs, **38.43 seconds**.
- Stored JSON coverage: location 80,925/82,836, EVSE 203,509/210,988, connector 204,211/211,698. Historical rows absent from current source deliberately retain NULL.
- No nested `evses` under location and no nested `connectors` under EVSE. All 204,211 imported connectors have JSON key `max_voltage`; field value validity not assessed here.
- Database size baseline **672 MB** (location 56 MB, EVSE 69 MB, connector 31 MB); after import **935 MB** (145 MB, 151 MB, 122 MB respectively), total growth **263 MB**. These are rounded relation sizes, including indexes and TOAST.
- Identical second bulk import succeeded in **28.82 seconds**; database remained **935 MB**, location **145 MB**, EVSE **151 MB**, connector **122 MB**. No measurable relation growth at this precision, consistent with no unnecessary rewritten rows; not independent proof of zero writes/WAL.

### Remaining validation / caveats

No automated regression tests or per-row exhaustive content comparison, WAL measurements, explain plans, API smoke test on this branch, or fault-injection test. Migration/backfill was tested locally only. The bulk import is transactional; its initial SQL failure resulted in rollback. No production deployment and no merge at the time of these measurements.

## Connector field coverage (local query)

Of 204,211 imported connector JSONB objects, 204,211 have non-empty `max_voltage`, 204,211 have non-empty `max_amperage`, and 139,016 (68.1%) have non-empty `max_electric_power`; the remaining 65,195 (31.9%) have no non-empty explicit power. This is **field presence**, not validation of numeric ranges or electrical semantics. Derived power is deferred to issue #64.
