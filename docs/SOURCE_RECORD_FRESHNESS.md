# Source record freshness API (2026-10-28)

The feed observation and source record modification timestamps convey distinct facts. A recently observed NDW snapshot can contain source statuses unchanged for weeks or years. Data audit identified 16 operators with no location or EVSE changes in >30 days (863 EVSEs, 587 marked AVAILABLE); this does **not** establish the actual current operational status.

## Additive JSON fields
`sourceAgeSeconds` and `sourceFreshness` are available on location list entries, location details, individual EVSE detail entries, and map viewport locations (where the existing `lastUpdated` remains as before).

Classification (inclusive upper bounds): RECENT <=24 h; AGING >24 h through 7 d; OLD >7 d through 30 d; VERY_OLD >30 d; UNKNOWN for absent or future timestamps. Age is integer elapsed seconds, floor rounded. Values are computed at serialization, so age advances without a source update.

These fields describe **source-update age**, not a measured station-health score, confidence level, or evidence of a live status check by the charge point operator.

## Compatibility and preservation
- `isStale` retains its existing 15-minute source modification meaning.
- `feedLastObservedAt` and `feedIsStale` retain their separate feed-observation meaning.
- Raw NDW timestamps, EVSE status, availability counts and historical profiles are not modified.
- No DB schema migration and no Android client changes.
- Future or missing timestamps use UNKNOWN/null rather than implying freshness.

The API selftest covers all age boundaries, future/missing timestamps and a known old September 2026 timestamp.
