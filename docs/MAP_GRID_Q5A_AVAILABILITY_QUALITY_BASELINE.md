# ChargeWatch #113 — Q5a NDW-beschikbaarheidskwaliteit: nulmeting

**3 november 2026. Alleen onderzoeksontwerp; nog niet uitgevoerd. Geen schema- of API-wijziging.**

## Bestaande implementatie / contract
- `ApiOptions` gebruikt `CW_API_STALE_AFTER_MINUTES`, met **standaard 15 minuten**, instelbaar tussen 1 en 1440. Dit is de *bestaande API-instelling*, niet noodzakelijk de definitieve overview-ringregel.
- `ApiDatabase` controleert enerzijds de ouderdom van `snapshot_poll_checkpoint.last_observed_at` als feed-observatietijd en anderzijds `source_last_updated` als afzonderlijke bronupdate. Een ongewijzigde brontimestamp betekent **niet automatisch** dat de NDW-feed verouderd is.
- De bestaande API telt AVAILABLE, CHARGING/BLOCKED/RESERVED, INOPERATIVE/OUTOFORDER en overige/onbekende statussen apart. PLANNED/REMOVED horen niet tot operationele capaciteit. De EVSE-presence-reconciliatie maakt historisch aanwezig nadrukkelijk anders dan actief.
- Het historische EWMA-/snapshotstatistiekontwerp waarschuwt: geen nieuwe fysieke beschikbaarheidsinformatie afleiden uit cache-hergebruik, een ontbrekende EVSE of een downloadgap. Onvolledige dekking niet als rood of groen presenteren.

**Voor Q5 nog vast te stellen:** welke bronwaarnemingen voldoende actueel en compleet zijn om een groen/rood ringsegment te legitimeren; hoe UNKNOWN/null en fout-/geblokkeerd onderscheiden worden; hoe clustersegmenten exact sommeren; welke responsemetadata die onzekerheid inzichtelijk maken. Q4c-08 voorlopige `green`/ `red` is *niet* het definitieve Q5-besluit.

## Q5a NAS nulmeting — read-only, kleine Oss-bbox

De volgende query toont de **huidige statusverdeling onder actieve operationele EVSE's** in de al gebruikte kleine Oss-bbox. Vervolgens toont hij uitsluitend de laatste feed-waarneming (geen conclusie over individuele EVSE-freshness). De twee tellingen zijn bewust gescheiden.

```bash
cd /volume2/docker/chargewatch
sudo docker compose exec -T db psql -X -v ON_ERROR_STOP=1 \
  -U chargewatch_owner -d chargewatch <<'SQL'
SET ROLE chargewatch_read;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL jit = off;

WITH chosen AS MATERIALIZED (
  SELECT l.id
  FROM charging_location l
  WHERE l.is_active AND l.position IS NOT NULL
    AND ST_Intersects(
      l.position,
      ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
),
classified AS (
  SELECT e.id,
         upper(btrim(coalesce(e.status,''))) AS status
  FROM chosen l
  JOIN evse e ON e.location_id=l.id AND e.is_active
)
SELECT
  count(*)::bigint AS active_evse_rows,
  count(*) FILTER (WHERE status IN ('PLANNED','REMOVED'))::bigint AS excluded_lifecycle,
  count(*) FILTER (WHERE status NOT IN ('PLANNED','REMOVED'))::bigint AS operational_evse_rows,
  count(*) FILTER (WHERE status='AVAILABLE')::bigint AS available,
  count(*) FILTER (WHERE status IN ('CHARGING','BLOCKED','RESERVED'))::bigint AS occupied_or_blocked,
  count(*) FILTER (WHERE status IN ('INOPERATIVE','OUTOFORDER'))::bigint AS fault,
  count(*) FILTER (WHERE status NOT IN
    ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER',
     'PLANNED','REMOVED'))::bigint AS unknown_or_other,
  count(*) = (
    count(*) FILTER (WHERE status IN ('PLANNED','REMOVED')) +
    count(*) FILTER (WHERE status='AVAILABLE') +
    count(*) FILTER (WHERE status IN ('CHARGING','BLOCKED','RESERVED')) +
    count(*) FILTER (WHERE status IN ('INOPERATIVE','OUTOFORDER')) +
    count(*) FILTER (WHERE status NOT IN
      ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER',
       'PLANNED','REMOVED'))
  ) AS partition_ok
FROM classified;

SELECT
  max(last_observed_at) AS feed_last_observed_at,
  round(extract(epoch FROM (now()-max(last_observed_at)))/60,1)
    AS feed_observation_age_minutes
FROM snapshot_poll_checkpoint;

ROLLBACK;
RESET ROLE;
SQL
```

## Acceptatie en interpretatie
De eerste resultset moet `partition_ok=t` hebben. De tweede is diagnostiek voor het feed-waarnemingsmoment; `NULL` of een onwaarschijnlijk toekomstig tijdstip verdient onderzoek. Niet afleiden dat **elke** individuele EVSE actueel is alleen omdat de globale feed-checkpoint vers is. Voor de betrouwbaarheid van de nieuwe overview moet Q5b apart analyseren of een recente geldige en volledige locatie-waarneming/bronpublicatie bewezen kan worden.

**Na Q5a**: werk de classificatie-beslisboom uit met expliciete fixturematrix voor 0/n operationeel, AVAILABLE + UNKNOWN, geen AVAILABLE + UNKNOWN, alle CHARGING, fault-only, stale feed, onvolledige locatiedekking, PLANNED/REMOVED, en tijdsgrens 15 minuten. Geen implementatie zonder ontwerpbesluit/akkoord.

## Open Q4-work

Q4c-04c synthetische deduplicatie en Q4c-08b positieve locatiebrede ringfixture zijn voorbereid maar de gebruiker heeft hun resultaten nog niet aangeleverd. Die twee blijven open en mogen niet stilzwijgend als geslaagd worden gemarkeerd.


## Werkelijke NAS-uitvoer — 3 november 2026

Gebruiker voerde beide read-only Q5a-queries succesvol uit in de kleine Oss-bbox:

| active_evse_rows | excluded_lifecycle | operational_evse_rows | available | occupied_or_blocked | fault | unknown_or_other | partition_ok |
|---:|---:|---:|---:|---:|---:|---:|---|
| 206 | 2 | 204 | 151 | 48 | 1 | 4 | t |

Feedwaarneming: `2026-11-03 11:32:29.014798+00` (12:32:29.014798 CET), `feed_observation_age_minutes=5.8` op het meetmoment.

**Q5a PASS (verdelingsinvariant):** 206 = 2 uitgesloten lifecycle + 151 AVAILABLE + 48 CHARGING/BLOCKED/RESERVED + 1 fault + 4 UNKNOWN/overig. De feed-checkpoint was op dat specifieke meetmoment jonger dan de huidige standaarddrempel van 15 minuten; dit is **geen bewijs van individuele EVSE-actualiteit of complete dekking**. Q5b moet deze aspecten op locatie- en bronwaarnemingsniveau afzonderlijk beoordelen. Geen mutaties, merge of deployment.
