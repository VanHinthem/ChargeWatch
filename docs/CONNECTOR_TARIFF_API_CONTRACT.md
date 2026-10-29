# Connector tariff API contract — implemented (#66)

ChargeWatch is NL-only; all tariff evaluation uses `Europe/Amsterdam`. The API is not live, so breaking changes are allowed.

## Location detail response

In `GET /api/v1/locations/{id}`, each connector exposes `tariffStatus` and `tariffOptions[]` instead of the old `tariffs[]` array.

- `NO_REFERENCE`: connector has no tariff reference, empty options.
- `SOURCE_MISSING`: a referenced source tariff is not in the imported tariff table; reference is preserved, option has no components.
- `MULTIPLE_OPTIONS`: more than one referenced tariff, none missing. Do not silently choose one tariff as final.
- `APPLICABLE`: one option, all selected components unconditionally applicable.
- `CONDITIONAL`: one option has session-dependent or uncertain components.
- `UNAVAILABLE`: one reference exists but cannot select components.
- `MALFORMED`: source tariff exists but its element structure is invalid and yields no usable components. A partially valid tariff can still return independent usable components with diagnostic limitations.

Each option includes source identifiers, currency, sourceUpdatedAt, asOfUtc, status, selected per-dimension components with string certainty and limitations. The API also exposes parsed `minimumPrice`, `maximumPrice`, `alternativeUrl` and `hasAlternativeText` when supplied. Each component exposes `vatStatus` (`EXPLICIT_RATE` or `NOT_SPECIFIED`) and optional `vatPercent`; these do not establish the final consumer price. The response uses a single UTC evaluation instant per location request. Prices are source CPO components, **not** final eMSP prices or complete charging-session totals. Unknown VAT must remain unknown.

The SQL LEFT JOIN preserves `connector_tariff` source identifiers even when `tariff` is null. The tariff API and database migrations 018 and 019 are deployed and validated on NAS. The Android client will be updated in a separate phase after backend validation.

## Tests and rollout

`dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test` checks the connector tariff contract; CI also builds and validates the API. Deployment validation completed on NAS against real UB2 (location 67381) and EFL tariffs (locations 82929 and 97917). Dashboard and recurring integrity reports remain deferred to #79.

## Independent NDW Tariffs feed provenance

Migration `018_tariffs_feed_checkpoint.sql` creates a single-row `ndw_tariffs_feed_checkpoint` table, readable by the read-only API role. The collector persists a successful Tariffs HTTP 200 or 304 check **after** its import/cache processing succeeds. The checkpoint is not updated for skipped or failed Tariffs checks. The API returns `tariffsFeedLastCheckedAt` and `tariffsFeedIsStale` independently on location detail and `/api/v1/data-status`. The default stale threshold is two hours, reflecting the collector's default 60-minute polling interval. On first rollout the state is unknown/stale until the collector completes a successful check; no historic time is fabricated.

Tariff applicability is **not** the same as source freshness: `APPLICABLE` describes the rule selection only, while `tariffsFeedIsStale=true` indicates that the source check is too old for a trusted current-price claim. Android must respect both signals. Tariff `sourceUpdatedAt` is the upstream record's timestamp, not the feed's last verification time.

## Presence in the current Tariffs snapshot

A stored source payload is not by itself evidence that the tariff is still present in NDW. Migration `025_tariff_snapshot_presence` therefore records current presence separately with `tariff.is_active` and `last_seen_at`, plus a single-row `ndw_tariffs_snapshot_checkpoint`.

Presence is reconciled only inside a successful bulk transaction for a changed, validated Tariffs HTTP 200 response. A tariff missing from that accepted snapshot becomes inactive while its payload and `connector_tariff` reference remain stored. The location API joins only active tariffs, so the preserved reference is returned as `SOURCE_MISSING` with no price components. A later accepted snapshot containing the same tariff reactivates it. Tariffs HTTP 304, cache replay and manual `--bulk` do not infer absence.

Before reconciliation, the importer rejects fewer than `CW_NDW_MIN_TARIFFS` unique tariffs (production default 1.000) and shrinkage greater than 10% from the last accepted Tariffs snapshot count. On the first verified snapshot after migration, the active tariff count is the fallback baseline. A rejected or failed import advances neither presence state nor source checkpoints/cache. This is a safety guard against empty or obviously partial feeds, not proof that every smaller upstream change is complete.

`/api/v1/data-status.tariffCount` counts active tariffs. Tariffs-feed freshness remains independent: a recent successful feed check can be fresh while a particular preserved connector reference is `SOURCE_MISSING`.

## Acceptance and deployment

Deployed releases include migration 018 (checkpoint table and read-role grant) and migration 019 (collector import-role write grant). A successful HTTP 304 produced a verified checkpoint timestamp, which was returned identically by the read-only API with `tariffsFeedIsStale=false`. Runtime validation completed; issue #66 closed. Automated historical monitoring remains deferred to issue #79. The real UB2/EFL/EFL-duration validations from PR #80 remain relevant.

## Delivery status (2026-10-28)

Production NAS release: `7a7e9425369b5b04503b2c46d94d66d66fc99b08` (PR #82). The tariff API work in PRs #80 and #81 and the migration permission fix in #82 are merged into `main`; all corresponding CI checks passed. Database and API checks succeeded. Android integration is intentionally the next phase. No claim that a restore to an isolated database has been performed.
