# ChargeWatch #113 — P1d bestaande EVSE-indexen inspecteren

**3 november 2026 — voorbereid, nog niet uitgevoerd.** Oss: A1 directe join 121,861 ms versus A2 landelijke preaggregatie 496,361 ms. Beide scannen 216.219 actieve EVSE's, terwijl slechts 2.101 locaties (5.099 A1-joinrijen) geselecteerd zijn. Eerst controleren welke bestaande indexen PostgreSQL kan benutten; **geen nieuwe index aanmaken**.

## Read-only NAS-controle

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='1s';

SELECT schemaname,tablename,indexname,indexdef
FROM pg_indexes
WHERE schemaname='public'
  AND tablename IN ('evse','charging_location')
ORDER BY tablename,indexname;

SELECT relname AS table_name,
       n_live_tup AS estimated_live_rows,
       seq_scan,idx_scan,n_tup_ins,n_tup_upd,n_dead_tup,
       last_analyze,last_autoanalyze
FROM pg_stat_user_tables
WHERE schemaname='public'
  AND relname IN ('evse','charging_location')
ORDER BY relname;

ROLLBACK;
RESET ROLE;
SQL
```

## Waarom en vervolg

- Kijk of een bestaande index met voorste sleutel `location_id` bestaat en of active EVSE's effectief via een partial index zijn te selecteren.
- Vergelijk met het A1-plan: PostgreSQL koos bij 2.101 geselecteerde locaties een landelijke `Seq Scan`, mogelijk omdat die in de gekozen kostenschatting voordeliger was. Een index **hebben** betekent niet dat de planner die in deze queryvorm **kiest**.
- Als een geschikte index bestaat, analyseer eerst of selectie vóór join met de bestaande A3 LATERAL-variant aantoonbaar tot beter gedrag leidt. A3 was al eenmaal gemeten: Oss 104,811 ms, grote regio 1100,014 ms; vermijd dezelfde proef zonder expliciete reden te herhalen.
- Pas bij aangetoonde bottleneck en na nieuwe, gecontroleerde index-plannen eventueel een gerichte query-optimalisatie voorstellen. **Geen schemawijziging zonder afzonderlijk akkoord**.

PR #114 draft; geen code-, DB-, NAS- of mergeactie.


## Werkelijke NAS-uitvoer — 3 november 2026

Gebruiker voerde P1d read-only uit. Relevante bestaande indexen:

- `idx_evse_active_location`: **btree(location_id) WHERE is_active** (bestaat al, dus geen nieuwe index voorstellen).
- `evse_identity`: unieke btree `(location_id, source_uid)`.
- `ix_charging_location_active_position`: GiST(position) WHERE is_active AND position IS NOT NULL.
- `ix_location_position`: algemene GiST(position).
- Beide tabellen hebben primaire-sleutel-indexen; charging_location ook unieke `location_identity`.

| tabel | estimated_live_rows | seq_scan | idx_scan | n_dead_tup | last_autoanalyze |
|---|---:|---:|---:|---:|---|
| charging_location | 85.666 | 33.491 | 471.510.149 | 0 | 2026-11-03 12:39:31.594269+00 |
| evse | 216.362 | 15.494 | 3.698.372.485 | 27.621 | 2026-11-03 12:44:33.350734+00 |

`pg_stat_user_tables.idx_scan` is een cumulatieve index-scancounter, **geen individuele SQL-querymetriek**. `n_dead_tup` is een schatting en kan door collectorupdates schommelen; dit rechtvaardigt niet zonder verder bewijs een handmatige VACUUM of indexrebuild. `last_autoanalyze` toont recente analyse, geen garantie dat de optimizer iedere gewenste index kiest.

**Conclusie:** de locatiegerichte partial index bestaat. De A1 Oss-plan koos niettemin een landelijke Seq Scan over 216.219 actieve EVSE-rijen. Dit is PostgreSQL's kostenafweging, niet bewijs van een ontbrekende index. **Volgende stap** is een kleine read-only benchmark van een reeds eerder geteste indexvriendelijke A3 LATERAL-strategie tegenover A1, met exact dezelfde Oss-cellen (eventueel EXPLAIN only voor indexpad), voordat enige code-/index-/schema-optimalisatie wordt besloten. Geen mutaties, merge of deploy.


## Werkelijke A3-indexplanhermeting — 3 november 2026

Gebruiker voerde de bestaande A3-`LATERAL`-proef uit op het kleine Oss-grid (2.101 locaties, 4 cellen) en leverde het volledige `EXPLAIN (ANALYZE, BUFFERS)`-plan:

| Maatstaf | A1 recente Oss-run | A3 LATERAL-indexrun | A2 recente Oss-run |
|---|---:|---:|---:|
| Execution Time | **121,861 ms** | **152,302 ms** | 496,361 ms |
| Planning Time | 25,396 ms | 30,399 ms | 27,933 ms |
| Shared buffers read | 30.422 | **7.308** | 18.726 |
| Shared buffers written | niet vermeld | **6.688** | 2.329 |
| Geselecteerde locaties | 2.101 | 2.101 | 2.101 |

A3-plan: `Nested Loop Left Join` met `Aggregate` en **`Index Scan using idx_evse_active_location` 2.101 keer**, gemiddeld 2,43 actieve EVSE-rijen per locatie. EVSE-indexscan rapporteert `Buffers: shared hit=5959 read=5081 dirtied=99 written=4506`. Eindaggregatie `GroupAggregate` met in-memory quicksort (195 kB), geen aangetoonde temp-spill. Locatieselectie gebruikt de partial GiST-index en parallel bitmap heap scan; A3-root buffers `hit=6714 read=7308 dirtied=99 written=6688`.

**Interpretatie:** de bestaande partial btree-index is daadwerkelijk bruikbaar, maar 2.101 indexzoekacties waren in deze losse run trager dan de A1-Seq Scan. Bufferwrites in read-only uitvoering zeggen niet dat applicatierijen zijn gewijzigd; dit is runtime-I/O en kan met NAS-/collectorbelasting samenhangen. Geen bewezen permanente winst van A3 en geen aanleiding voor een nieuwe index of VACUUM op basis van alleen deze vergelijking.

**Vervolgadvies:** voorkom eindeloze losse SQL-varianten. Baseline A1 als eenvoudige startkandidaat, maar bevestig uiteindelijk op de **echte gefilterde overview-query** en daarna nationale schaal, omdat eerdere A1/A2/A3-benchmarks ongefilterde proefclassificatie gebruiken. Dit is een meetadvies, geen API-implementatieakkoord. PR #114 draft; geen deploy/merge.
