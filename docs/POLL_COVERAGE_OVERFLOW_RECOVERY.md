# EVSE poll-hour coverage overflow recovery

## Production observation (2026-10-27)
Release sha-4846fa1 migrated 015–017 successfully with pre-migration backup. A preserved HTTP 200 Locations replay then failed transactionally with PostgreSQL 23514: evse_poll_hour_coverage_covered_seconds_check. Previously committed NDW checkpoint was 2026-10-27 13:59:07Z. The DB has ~10.96M EVSE-hour rows, including ~8.69M fully covered hours; records at 13:00Z were often nearly saturated.

## Narrow safety patch
- Before the existing coverage upsert, calculate how many proposed EVSE-hour increments would overflow either covered_seconds or available_seconds. Log an explicit WARNING with rejected hour count.
- For an existing hour, only apply an increment if both updated counters stay within 3600. Preserve existing rows unchanged where the new increment cannot safely fit. Do **not** clip to 3600, erase history, drop constraints or falsify availability ratios.
- The checkpoint and catalogue still progress transactionally. Not inserting the ambiguous incremental coverage means coverage may be understated for affected EVSE-hours. These ambiguous periods require eventual interval-provenance-based reconciliation, because aggregate seconds alone do not identify the already-covered time spans.
- Other postgres exceptions still roll back the full transaction.

## Limitations / validation needed
- This is a production-unblocking guard, **not** precise interval de-duplication: under-3600 overlapping samples may still double-count. Long-term fix requires storing interval bounds per EVSE and maintaining a nonoverlapping union.
- A saturated hour with new known-safe nonoverlapping time cannot always be distinguished from a replay using current aggregate schema; suppress and report such entries.
- Validate build/CI, staged simulation of an overflowing EVSE-hour, collector replay successful completion, active locations and typed JSONB field coverage, and unchanged old history.
- No migration needed for this patch; UGREEN rollout still requires its automatic backup/checkpoint. Do not deploy or merge before user approval and checks.
