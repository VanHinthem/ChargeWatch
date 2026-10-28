# Connector tariff API contract — pre-release (#66)

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

The SQL LEFT JOIN preserves `connector_tariff` source identifiers even when `tariff` is null. No DB migration or production changes. The Android client will be updated in a separate phase after backend validation.

## Tests and rollout

`dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test` checks the connector tariff contract; CI also builds and validates the API. Before deploying, review a real missing UB2 reference and a known valid EFL tariff. Dashboard and recurring integrity reports remain deferred to #79.

## Independent NDW Tariffs feed provenance

Migration `018_tariffs_feed_checkpoint.sql` creates a single-row `ndw_tariffs_feed_checkpoint` table, readable by the read-only API role. The collector persists a successful Tariffs HTTP 200 or 304 check **after** its import/cache processing succeeds. The checkpoint is not updated for skipped or failed Tariffs checks. The API returns `tariffsFeedLastCheckedAt` and `tariffsFeedIsStale` independently on location detail and `/api/v1/data-status`. The default stale threshold is two hours, reflecting the collector's default 60-minute polling interval. On first rollout the state is unknown/stale until the collector completes a successful check; no historic time is fabricated.

Tariff applicability is **not** the same as source freshness: `APPLICABLE` describes the rule selection only, while `tariffsFeedIsStale=true` indicates that the source check is too old for a trusted current-price claim. Android must respect both signals. Tariff `sourceUpdatedAt` is the upstream record's timestamp, not the feed's last verification time.

## Acceptance and deployment

This PR requires migration 018 before collector/API startup. Verify read-only grants, an HTTP 304 advancing `tariffsFeedLastCheckedAt` without changing `sourceUpdatedAt`, and stale/unknown behavior before considering #66 complete. The real UB2/EFL/EFL-duration validations from PR #80 remain relevant.
