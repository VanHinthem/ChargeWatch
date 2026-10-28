# Operational EVSE capacity in public location/map API

## Data-first evidence (28 October 2026)
Read-only production measurements: 203,342 EVSEs present at 80,837 active
locations; 5,251 have OCPI status REMOVED, 20 PLANNED. Affected location
totals: 1,997; excess EVSEs: 5,271; maximum excess at one location: 144.
A status of REMOVED does not mean absent from the NDW snapshot.

## API contract
- Keep `evse.is_active` as *source snapshot presence* only. Do not modify
  importer, DB, presence reconciliation or historical statistics.
- For location search `evseCount` and map viewport `totalCount` count only
  present EVSEs whose normalized OCPI status is not REMOVED or PLANNED.
- The map's connector categories represent the same operational set. They
  exclude connectors belonging to those statuses even if source-present.
- AVAILABLE alone counts as currently available. UNKNOWN, CHARGING, BLOCKED,
  RESERVED, OUTOFORDER, INOPERATIVE remain in the operational denominator.
- Location details keep all present EVSEs with explicit status, including
  REMOVED/PLANNED (for diagnostic transparency). EVSE histories unchanged.
- `/api/v1/data-status` EVSE/connector counts remain source-presence inventory,
  not operational capacity: do not silently change metric semantics.
- Locations with zero operational EVSEs remain in location search/viewport
  with zero counts; hiding those locations is a separate product decision.

## Validation and limits
Use isolated PostgreSQL+HTTP API fixture including 144 removed EVSEs,
one planned and a live AVAILABLE EVSE, inactive historical parent and connector;
verify location list, viewport category counts, details and data-status inventory.
No NAS deployment or merge without explicit authorization. The baseline
production SQL aggregates are historical evidence, not synthetic test output.
