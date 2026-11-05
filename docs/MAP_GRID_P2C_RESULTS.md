# ChargeWatch #113 — P2c resultaten: herhaalde gefilterde overview-runs

**3 november 2026 — daadwerkelijk uitgevoerd op NAS.** De gebruiker leverde het volledige `p2c-paired-result.txt` aan, met acht gelabelde `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`-plannen. Alle acht zijn voltooid zonder gerapporteerde SQL-fout of timeout. Elke variant gebruikt de echte productie-`MapFilter`-predicate in de bestaande benchmarkexporters. Deze metingen zijn **uitsluitend read-only**, geen API-implementatie.

## Alle acht uitvoeringen

| Scenario | Run | Variant | Locaties | Cellen | Execution ms | Planning ms | Root shared hit | Root shared read | Root shared written | Connector landelijke Seq Scan |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| `ccs2_dc` | 1 | original | 34 | 4 | 200,823 | 28,148 | 24.913 | 31.761 | 4.771 | ja |
| `ccs2_dc` | 2 | geography-first | 34 | 4 | 136,676 | 3,143 | 26.864 | 26.148 | 1.356 | ja |
| `ccs2_dc` | 3 | geography-first | 34 | 4 | 138,138 | 3,216 | 29.425 | 23.587 | 181 | ja |
| `ccs2_dc` | 4 | original | 34 | 4 | 134,760 | 3,081 | 32.814 | 23.740 | 444 | ja |
| `ccs2_dc_minfree1` | 1 | original | 32 | 4 | 113,278 | 3,335 | 34.473 | 23.074 | 424 | ja |
| `ccs2_dc_minfree1` | 2 | geography-first | 32 | 4 | 97,247 | 3,136 | 21.583 | 7.415 | 4.792 | nee |
| `ccs2_dc_minfree1` | 3 | geography-first | 32 | 4 | 62,337 | 3,194 | 25.940 | 3.050 | 110 | nee |
| `ccs2_dc_minfree1` | 4 | original | 32 | 4 | 149,672 | 3,409 | 31.923 | 25.624 | 121 | ja |

### Gemiddelden (twee samples per variant)

| Scenario | Original | Geography-first | Gedrag |
|---|---:|---:|---|
| CCS2+DC | 167,792 ms | 137,407 ms | gemengd: oorspronkelijke tweede run 134,760 ms tegenover geography-first 136–138 ms; **beiden blijven landelijke connector-Seq Scan** |
| CCS2+DC+minFree1 | 131,475 ms | 79,792 ms | geography-first beide runs sneller; **lokale EVSE-/connector-indexzoekacties in plaats van landelijke connector-Seq Scan** |

## Queryplanverklaring

1. **CCS2+DC:** de `geo_locations AS MATERIALIZED` CTE selecteert 2.101 locaties via de partiële GiST-index, maar PostgreSQL construeert nog altijd eerst de landelijke technische EVSE-matchset: connector Parallel Seq Scan (ca. 6.473 passende connectorrijen), EVSE-PK-lookups, en vervolgens Hash Semi Join met de geografische locaties. Een gematerialiseerde locatie-CTE **garandeert geen location-first plannerstrategie**. Geen structurele IO-winst aangetoond.
2. **CCS2+DC+minFree1:** geography-first materialiseert de 2.101 geografische locaties, voert dan voor elke locatie de `minFree`-subquery uit met `idx_evse_active_location` en `idx_connector_active_evse`, en doet pas voor passende locaties een extra technische EXISTS-check. Geen landelijke connector-Seq Scan in twee opeenvolgende runs. De root reads waren 7.415 en 3.050 versus 23.074 en 25.624 voor original. De cache en NAS-schijfbelasting verschillen, dus tijdwinst niet als gegarandeerde productielatency publiceren.
3. **Resultaatpariteit:** P2b toonde eerder exacte `EXCEPT`-ID-pariteit (34/34 en 30/30 bij eerdere snapshot); P2c toont bij **ieder scenario gelijke aantallen en hetzelfde gridcellen-aantal** tussen beide queryvormen: 34/4 en 32/4. **P2c heeft geen nieuwe exacte EXCEPT-ID-pariteit per run uitgevoerd**; de stijging van 30 naar 32 bij gecombineerde filter is plausibel door gewijzigde feeddata en geen bewijs van afwijking.
4. **Meetbeperkingen:** de eerste `ccs2_dc` original run heeft 28,148 ms Planning Time versus ~3 ms in de overige zeven runs en meer fysieke reads; warm-/cold-cache is niet gecontroleerd. Vier runs per filter volstaan niet voor p95- of schaalclaims. De benchmarkstatus-classifier is niet het uiteindelijke productiecontract.

## Conclusie en opvolging

**Geography-first is een bewezen filterparitaire ontwerpvariant voor de twee P2b-fixtures en een veelbelovende optimizerhypothese voor het gecombineerde filter. Geen algemene winnaar.** Het volgende werkpakket is **één gerichte locatiegecorreleerde techniekfilter-variant voor CCS2+DC**, waarbij de bestaande productiefilterlogica als enige bron blijft dienen en ID-pariteit vóór perf wordt afgedwongen. Maak geen handgeschreven afwijkende filtersemantiek. Als zonder nieuwe productieschema's geen herbruikbare oplossing blijkt, beoordeel ook landelijke dekking/schaal vóór besluitvorming. Herhaal niet alle A1–A3-varianten of bestaande NDW-statusonderzoeken.

Geen productie-API-/Android-code, DB-schemawijziging, merge of deployment. PR #114 blijft draft.
