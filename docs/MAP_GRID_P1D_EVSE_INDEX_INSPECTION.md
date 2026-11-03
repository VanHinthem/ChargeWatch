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
