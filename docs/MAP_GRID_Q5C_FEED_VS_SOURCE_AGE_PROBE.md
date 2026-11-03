# ChargeWatch #113 — Q5c: NDW-feedwaarneming versus bronupdate

**3 november 2026 — ontwerp en read-only meetplan, nog niet uitgevoerd.**

## Aanleiding

Q5a en Q5b zijn uitgevoerd. Q5a: 206 actieve EVSE-records, waarvan 204 operationeel (151 AVAILABLE, 48 CHARGING, 1 OUTOFORDER en 4 UNKNOWN); de globale feedwaarneming was op meetmoment 5,8 minuten oud. Q5b: 98 locaties, waarvan 86 minimaal één AVAILABLE en geen UNKNOWN, 10 nul AVAILABLE zonder UNKNOWN en 2 nul AVAILABLE met UNKNOWN. De oudste bronupdate van een AVAILABLE EVSE was ongeveer 84,7 dagen oud.

Dat laatste **bewijst niet** dat de beschikbaarheid al 84,7 dagen niet meer via een onafhankelijke NDW-fetch is bevestigd.

## Huidig code- en datacontract

- `ApiDatabase.GetDataStatus()` leest `max(snapshot_poll_checkpoint.last_observed_at)` als laatste feedwaarneming en `max(import_run.completed_at)` als laatste voltooide import.
- `ApiOptions` gebruikt `CW_API_STALE_AFTER_MINUTES`: standaard 15 minuten, configureerbaar van 1 tot 1440.
- `evse.source_last_updated` is een bronupdate-timestamp en hoeft bij ongewijzigde status niet te veranderen.
- Het ontwerp voor compacte statistieken onderscheidt onafhankelijke succesvolle fetch, gewijzigde publicatie, ongewijzigde feed, cache-hergebruik en mislukte fetch. Een ongewijzigde feed is geen bewijs van uitval, maar een hergebruikte cache is niet een nieuwe onafhankelijke waarneming.
- Bronstatus, feedbereikbaarheid, verificatie van complete locaties en individuele EVSE-presence zijn verschillende dimensies.

## Meetplan Q5c (NAS, read-only)

Deze probe onderzoekt de spreiding van brontimestamps in de eerder gebruikte Oss-bbox en vergelijkt die met het globale feedcheckpoint. Het is **geen onafhankelijke verificatie van per-EVSE publicatie-actualiteit**.

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

SELECT
 (SELECT max(last_observed_at) FROM snapshot_poll_checkpoint) AS feed_last_observed_at,
 round(extract(epoch FROM (now() -
   (SELECT max(last_observed_at) FROM snapshot_poll_checkpoint)))/60,1) AS feed_age_minutes,
 (SELECT max(completed_at) FROM import_run WHERE status='completed') AS last_completed_import_at;

WITH chosen AS MATERIALIZED (
  SELECT l.id FROM charging_location l
  WHERE l.is_active AND l.position IS NOT NULL
    AND ST_Intersects(l.position,
      ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
),
ages AS (
  SELECT e.id, upper(btrim(coalesce(e.status,''))) AS status,
         extract(epoch FROM (now()-e.source_last_updated))/60 AS age_minutes
  FROM chosen l JOIN evse e ON e.location_id=l.id AND e.is_active
  WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
)
SELECT status, count(*)::bigint AS total,
 count(*) FILTER (WHERE age_minutes IS NULL)::bigint AS missing_source_time,
 count(*) FILTER (WHERE age_minutes < 0)::bigint AS future_source_time,
 count(*) FILTER (WHERE age_minutes BETWEEN 0 AND 15)::bigint AS source_0_15m,
 count(*) FILTER (WHERE age_minutes > 15 AND age_minutes <= 1440)::bigint AS source_15m_1d,
 count(*) FILTER (WHERE age_minutes > 1440 AND age_minutes <= 10080)::bigint AS source_1d_7d,
 count(*) FILTER (WHERE age_minutes > 10080)::bigint AS source_over_7d
FROM ages GROUP BY status ORDER BY total DESC, status;

ROLLBACK;
RESET ROLE;
SQL
```

## Interpretatie en acceptatie

Controleer dat de tijdsbanden plus ontbrekende/toekomstige bronupdates per status optellen tot `total`. Vergelijk `feed_age_minutes` met de bestaande API-drempel als diagnostiek, maar verklaar EVSE's niet automatisch stale op basis van `source_last_updated`. Een recente globale feedcheckpoint is evenmin onafhankelijk bewijs voor complete, actuele statusdekking van elke individuele locatie.

Voor het uiteindelijke Q5-ringcontract moet apart worden besloten hoe een bewezen AVAILABLE, nul AVAILABLE met UNKNOWN, nul AVAILABLE met volledig bekende operationele status, nul operationele capaciteit, onvolledige locatiedekking en een onbevestigde/stale feed worden verwerkt. Q4c-04c en Q4c-08b synthetische testresultaten zijn nog niet ontvangen. Geen API-, Android- of databasewijzigingen, deployment of merge; PR #114 blijft draft.
