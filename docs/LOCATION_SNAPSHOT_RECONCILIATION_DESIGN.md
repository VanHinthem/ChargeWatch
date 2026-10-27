# Issue #63 — Location snapshot reconciliation

**Status:** first technical design/review gate; implementation pending. Branch `feature/63-location-snapshot-reconciliation`.

## Confirmed code paths
- `BulkImporter.Run(args, verifiedLocationsFetchedAtUtc)` stages and validates all locations, EVSEs, connectors in one transaction, then upserts `charging_location`; absent location rows remain untouched.
- The watch path calls the importer with a verified Locations HTTP 200 fetch timestamp. A manual `--bulk`, tariff-only operation or HTTP 304 does not by itself provide this provenance.
- The API queries `charging_location` for search, viewport, NDW key lookup and detail; these must exclude inactive objects in the public current catalog.
- Availability/history/statistics are stored separately and must not be deleted or re-keyed.

## Schema

Add `charging_location.is_active boolean NOT NULL DEFAULT true` with a partial index appropriate for active Geo/identity queries (index selection after EXPLAIN). Optionally `deactivated_at timestamptz` for observability; do not confuse with original OCPI `last_updated`. Existing rows stay active until a verified snapshot reconciles them.

Keep the stable `charging_location.id` and foreign keys; never delete EVSE or connector trees in this issue. Deactivated records may remain available to internal historical queries.

## Full-snapshot contract

1. A **verified full NDW Locations HTTP 200** fetch triggers reconciliation only when validated as complete (nonempty, configured minimum, uniqueness check, structurally valid gzip+JSON, and explicit caller provenance).
2. Stage all source location identities `(country_code,party_id,id)`. Validate source before making current-catalog state changes.
3. Inside **the same transaction as all location/EVSE/connector upserts**, mark existing DB locations absent from the staged snapshot inactive; revive included locations as active, regardless of whether other attributes changed.
4. Do not perform deactivation for manual bulk import, cached reprocessing, tariff-only import, HTTP 304, or any failed/partial snapshot.
5. Upsert/marking must be idempotent. An existing inactive location reappearing must retain its original DB ID and history.
6. Rollback all changes on exceptions, timeout, cancellation, or interrupted transaction. Count affected activations/deactivations in import observability.
7. **Important:** minimum location count alone cannot prove completeness. The `verifiedLocationsFetchedAtUtc` parameter is a provenance signal, not sufficient by itself if the HTTP download was truncated yet parseable. Define fail-closed validation for suspicious deltas/low coverage, and operational override only with deliberate review. A changed valid but unusually small snapshot must not silently retire tens of thousands of locations.

## API and statistics behavior
- Current API surfaces (`/api/v1/locations`, `/api/v1/map/viewport`, by-NDW-ID resolver, `/api/v1/locations/{id}`) filter to active locations; by-NDW-ID for inactive must return not found.
- Current-catalog counts use active records; make counts semantics explicit. Existing historical statistics queried by internal ID remain associated with historical IDs, and the data-retention model stays unchanged. Decide explicitly whether historical endpoints for inactive IDs remain accessible or return a documented status.
- Add integration tests covering consistent search, map, detail and NDW-ID visibility for inactive and revived locations.

## End-to-end tests (isolated PostgreSQL)
1. Load full snapshot A with locations A+B, capture their IDs/historical metrics.
2. Load verified full snapshot B with A+C, assert B inactive, A active, C active, and active API results exclude B.
3. Load verified snapshot C with A+B+C, assert B reactivates with same ID/foreign keys/history.
4. Repeat C; assert no reactivation churn.
5. Manual `--bulk` with subset cannot deactivate absent data.
6. HTTP 304 cannot deactivate.
7. Duplicate identities, gzip corruption, malformed JSON, below-minimum and suspicious global count drop -> full rollback, no deactivation.
8. Fault injected just before commit -> no partly deactivated catalog/history.
9. Check no unexpected changes to `observation`, snapshot checkpoint, EWMA/statistics history.
10. Benchmark import and viewport-query performance; validate index impact.

## Implementation boundaries
This PR's **first commit is design-only** and must not be merged as a feature. Implement and run tests before switching draft to ready. Coordinate migrations with #65 (hybrid JSONB storage), but #63 may ship independently.

Reference: `docs/OCPI_DATA_COMPLETENESS_AUDIT.md` and issue https://github.com/VanHinthem/ChargeWatch/issues/63
