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

## Source distribution observation — local snapshot 27 October 2026

Grouped connector source JSONB results reveal dominant `AC_3_PHASE/230V/16A` (112,171; 48,314 missing explicit power), `AC_3_PHASE/230V/32A` (31,506; 9,280 missing), `AC_3_PHASE/230V/25A` (22,048; 1,973 missing), plus `AC_1_PHASE/230V/16A` (1,206; 1,027 missing). Also `AC_3_PHASE/400V/32A` (7,313; 54 missing) and `400V/16A` (1,494; 44 missing). The reported 400 V may represent line-to-line rather than OCPI's line-to-neutral three-phase convention; do not naively multiply 3×400×A and claim a charge point rating.

**Initial conservative rule (pending outlier distribution audit):** only derive AC power at line-neutral voltage in the plausible 220–240 V band. For AC_1_PHASE use V×A, AC_3_PHASE use 3×V×A. If voltage outside this band, source/current nonpositive or invalid, or the type is ambiguous, report `unknown` without inventing a power rating. Source-provided positive power wins. Validate current ranges before choosing a fixed amperage guard. Do not label the derived number as guaranteed achievable charging speed.

## Decision update — user approved informative DC upper bounds (27 October 2026)

Local real-source results for connector rows with non-NULL source_data:

| power_type | total | missing explicit power | positive source voltage and amperage |
| --- | ---: | ---: | ---: |
| AC_3_PHASE | 193536 | 61465 | 61465 |
| AC_1_PHASE | 3013 | 2609 | 2609 |
| DC | 7662 | 1121 | 1121 |
| **Total** | **204211** | **65195** | **65195** |

The user explicitly requests that available data should not be hidden by overly strict validation. **Updated presentation contract:** source power `22 kW`; derived AC nominal indication `≈ 22 kW`; DC theoretical maximum from independent voltage/current ratings `≤ 400 kW` (upper bound, **not guaranteed charger output**). Proposed provenance values `source`, `derived_ac`, `dc_upper_bound`, `unknown`.

For AC_3_PHASE, source includes both 230 V and 400 V. OCPI prescribes phase-neutral voltage, but some feeds likely publish line-line: apply `3*V*I` for documented phase-neutral values (e.g. 230 V), and `sqrt(3)*V*I` only when voltage is confidently interpretable as line-line (e.g. nominal 400 V); retain assumption metadata and do not silently claim an exact source-rated output. Explicit `max_electric_power` always wins. Values should be checked for finite/positive/overflow, but avoid arbitrary tight ranges that hide otherwise useful data. Examine odd voltages before committing classifier.

**Supersedes earlier conservative recommendation** not to display DC or 400 V derived estimates. Earlier sections above describe that initial proposal, not the final product decision. Automated tests must distinguish DC bound from nominal source power.

## Local integration results — 27 October 2026

- .NET 10 importer and API builds succeeded, plus Docker builds of both images.
- Migration 017 succeeded on isolated PostgreSQL test database (three ALTER TABLE operations, migration registration).
- Local bulk import committed 80,925 locations, 203,509 EVSEs, 204,211 connectors and 102,024 tariffs in 30.34 seconds.
- All 204,211 current-source connector records have format, max_voltage and max_amperage; **0 mismatches** between typed columns and source_data JSONB.
- Locally running API /api/v1/data-status returned HTTP 200.
- Actual detail endpoint responses: location 1 explicit 22,000 W with powerSource=source; location 2 three-phase AC 230V/16A returned 11,040 W and derived_ac; location 548 CHAdeMO DC 920V/109A returned 100,280 W and dc_upper_bound, plus CCS Combo 920V/190A returned 174,800 W and dc_upper_bound. All observed values match the implemented formulas. Different connectors on the same site rightly have different bounds.

Still pending: tests for inferred AC 400V line-to-line calculation, unexpected/outlier voltage/current values, API backwards compatibility across Android, automated regressions, second identical-import measurements, and Android presentation. No NAS deployment and no merge.
