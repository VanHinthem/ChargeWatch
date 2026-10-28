# ChargeWatch — EVSE presence reconciliation (issue #73)

## Verified production facts
On 28 October the NDW checkpoint showed 80,837 locations and 203,342 EVSEs. A nearby database read counted 203,404 EVSEs under active locations. The 62-row difference is not an exact matched-set comparison. Historical EVSE rows remain present, and the API previously included all EVSEs under active locations.

## Behavioral contract
- Preserve historical EVSE rows and profile data. Source status REMOVED is not the same as absence from the latest snapshot.
- Presence is updated only inside verified, committed HTTP 200 Locations imports; not on HTTP 304, tariff-only, unverified bulk, rollback or failed import.
- Reject suspect reductions greater than 10 percent in either locations or EVSE counts before reconciliation.
- Three independent changed-snapshot misses over at least ten minutes cause inactivation. A returning EVSE is reactivated on its first confirmed appearance.
- Keep last_seen_at as a daily coarse heartbeat to avoid rewriting the entire EVSE table every five minutes.
- Exclude inactive EVSEs and their connectors from API location/map/detail queries and new category/statistic observations; historical profiles remain.
- Existing records initially stay active until later verified snapshots supply missing evidence. No invented historical absence dates.

## Risk, testing and rollout
- A 10 percent national shrinkage guard does not detect every partially published feed. Repeated observations reduce false positives but are not proof of completeness.
- Rewriting active-hour aggregates from an in-flight hour is intentionally out of scope: only new samples reflect new presence state.
- CI must verify migration 021, changed/unchanged compact statistics, API compilation and regressions. Benchmark the 200k+ indexed staged identity reconciliation and WAL before merge.
- Deployment requires pre-migration backup, separate restore assurance, observed collector health and explicit user approval. Do not deploy or merge automatically.
- Findings should be reviewed as a focused implementation under the wider issue #73.