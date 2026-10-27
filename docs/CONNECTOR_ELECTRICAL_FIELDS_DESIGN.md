# Issue #64 — Connector electrical characteristics and power provenance

## Current baseline
- PR #68 stores source connector JSONB; source_data contains format, max_voltage, max_amperage for all 204,211 current test-snapshot connectors. Explicit max_electric_power exists for 139,016; 65,195 have none.
- The current typed connector row stores standard, power_type and max_electric_power. Preserve these and expose new source attributes as separately typed nullable fields.

## Proposed database and importer
- Migration 017 adds nullable connector.format TEXT, max_voltage INTEGER, max_amperage INTEGER, alongside existing max_electric_power (watts). Do not backfill with invented values.
- Transactional upsert updates new fields and source_data together and uses IS DISTINCT FROM to avoid unnecessary rewriting.
- Existing IDs, tariff relations and observations remain unchanged. Reuse controlled --bulk reimport for existing present-source rows; inactive legacy rows can remain NULL.

## Source semantics and power provenance
OCPI 2.2.1 connector specification: max_voltage is volts line-to-neutral for AC_3_PHASE; max_amperage is amperes; max_electric_power is explicitly published watts and can represent a lower equipment limit.

- If max_electric_power is strictly positive, publish it with provenance `source`. Never override explicit power.
- Otherwise, only when both voltage and amperage are positive, within defensible validated bounds, and power_type is AC_1_PHASE or AC_3_PHASE:
  - AC_1_PHASE: theoretical W = V × A.
  - AC_3_PHASE: theoretical W = 3 × V × A (OCPI line-neutral V).
  - Mark provenance `derived`, format for UI as approx (≈).
- AC_2_PHASE, AC_2_PHASE_SPLIT and DC: `unknown` if explicit power absent. Never infer DC as guaranteed V×A; voltage and current maxima need not be simultaneous.
- Avoid strict voltage/current bounds until source distribution/outlier audit is completed. Outliers and malformed data should produce unknown rather than absurd power.
- Stable API: expose format, maxVoltage, maxAmperage, explicitMaxElectricPower, effectivePowerWatts and powerSource (`source`, `derived`, `unknown`) without exposing entire JSONB. Keep backwards-compatible existing maxElectricPower.
- Android: keep connector standard, show source power normally, derived power with ≈ and explanatory semantics, socket/cable only if context merits display.

## Required validation
1. Inspect source_data distributions across AC/DC, voltage, amperage, nonpositive/outlier values.
2. Migration, build and real local --bulk; field round-trip vs source_data.
3. Unit tests source-priority; 230V×16A AC3=11040W; AC1=3680W; AC2/DC unknown; missing/non-positive/outliers unknown.
4. API contract compatibility; Android half/full sheets display not broken.
5. Idempotent second import; storage and performance deltas.
6. No NAS production change or merge without explicit approval.

Official connector spec: https://ocpi.github.io/openapi-specification/ocpi/2.2.1/roles/cpo-interface.html
