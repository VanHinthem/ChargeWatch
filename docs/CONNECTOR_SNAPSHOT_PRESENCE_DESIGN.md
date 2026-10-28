# Connector snapshot presence — issue #73 / migration 022

## Problem and evidence
On 28 October 2026 the verified NDW import reported 204,044 connector payloads,
while 204,050 connector rows belonged to active EVSEs in the database. This
difference of six has **not** been matched by source connector identity yet.
24,316 current connector records have no tariff reference; 179,734 have one.
A missing tariff is not evidence of a missing connector.

## Rules
- Identity remains (evse_id, source_id); never delete historic connector rows.
- After the guarded verified *complete* NDW locations HTTP 200 import, stage
  source connector IDs and reconcile transactionally, after upsert.
- Distinct consecutive missing snapshots only. Deactivate at >=3 after
  >=10 minutes since first absence. Replays of the same observation are inert.
- Reappearance reactivates immediately, preserving connector ID, tariff
  relationship history and clearing missing counters.
- No presence changes on HTTP 304, manual bulk imports, rejected shrinkage,
  or rolled-back imports. Ignore absent connectors on inactive parent EVSEs or locations.
- Active queries must require c.is_active AND e.is_active AND l.is_active.
  No change to EVSE-based capacity or historical statistic calculations.
- Fresh tariff links for a connector that *is present* continue to be
  reconciled as before; links to missing connectors are retained but
  excluded from live API data.

## Risks and verification
- Hysteresis permits an absent connector for roughly two further valid polls;
  this intentionally reduces false positives on transient source omissions.
- Migration defaults historical connector rows to active. Three new successful
  snapshots are required to classify removals.
- The importer currently does not store a connector count in its NDW checkpoint.
  An aggregate six-row discrepancy is *not* proof of six exact missing IDs.
- Required: schema smoke/replay/reappearance/tariff tests, live HTTP list/detail/
  viewport/data-status checks, nationwide import regression, migration and NAS
  release pipeline. Verify latest SHA CI and complete PR review before merge.
- No NAS deployment or squash merge without explicit approval.
