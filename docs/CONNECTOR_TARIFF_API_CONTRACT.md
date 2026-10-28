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

Each option includes source identifiers, currency, sourceUpdatedAt, asOfUtc, status, selected per-dimension components with string certainty and limitations. The response uses a single UTC evaluation instant per location request. Prices are source CPO components, **not** final eMSP prices or complete charging-session totals. Unknown VAT must remain unknown.

The SQL LEFT JOIN preserves `connector_tariff` source identifiers even when `tariff` is null. No DB migration or production changes. The Android client will be updated in a separate phase after backend validation.

## Tests and rollout

`dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --self-test` checks the connector tariff contract; CI also builds and validates the API. Before deploying, review a real missing UB2 reference and a known valid EFL tariff. Dashboard and recurring integrity reports remain deferred to #79.
