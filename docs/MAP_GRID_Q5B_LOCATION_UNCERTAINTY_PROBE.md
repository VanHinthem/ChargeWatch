# ChargeWatch #113 — Q5b locatie-onzekerheid en brontimestamps

**3 november 2026. Status: voorbereid, nog niet uitgevoerd.** Q5a liet in Oss 204 operationele EVSE's zien: 151 AVAILABLE, 48 bezet/geblokkeerd, 1 defect, 4 onbekend; `partition_ok=t`. Het globale checkpoint was bij Q5a 5,8 minuten oud. Het checkpoint bewijst niet dat iedere afzonderlijke EVSE een recente bronupdate heeft.

## Doel

Controleer onafhankelijk welke *werkelijke* NDW-statuswaarden achter de vier UNKNOWN/overig zitten, hoeveel locaties operationele onbekende statussen hebben, en hoe oud de geregistreerde `source_last_updated`-tijdstempels zijn. Bronupdate-ouderdom is **diagnostisch**: een ongewijzigde NDW-status mag ook bij een latere succesvolle feedwaarneming geldig blijven. Geen nieuwe feed-stalenessnorm invoeren voordat Q5 beslist welke velden de betrouwbaarheid onderbouwen.

## NAS read-only query

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
    AND ST_Intersects(l.position,
      ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
)
SELECT
  upper(btrim(coalesce(e.status,''))) AS raw_status,
  count(*)::bigint AS evse_count,
  count(*) FILTER (WHERE e.source_last_updated IS NULL)::bigint AS null_source_timestamp,
  round(min(extract(epoch FROM (now()-e.source_last_updated)))/60,1) AS youngest_source_age_min,
  round(max(extract(epoch FROM (now()-e.source_last_updated)))/60,1) AS oldest_source_age_min
FROM chosen l JOIN evse e ON e.location_id=l.id AND e.is_active
WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
GROUP BY upper(btrim(coalesce(e.status,'')))
ORDER BY evse_count DESC,raw_status;

WITH chosen AS MATERIALIZED (
  SELECT l.id
  FROM charging_location l
  WHERE l.is_active AND l.position IS NOT NULL
    AND ST_Intersects(l.position,
      ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
),
per_location AS (
  SELECT l.id,
    count(e.id) FILTER (
      WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
    )::bigint AS operational,
    count(e.id) FILTER (WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE')::bigint AS free,
    count(e.id) FILTER (
      WHERE e.id IS NOT NULL AND upper(btrim(coalesce(e.status,''))) NOT IN
      ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')
    )::bigint AS unknown
  FROM chosen l LEFT JOIN evse e ON e.location_id=l.id AND e.is_active
  GROUP BY l.id
)
SELECT
  count(*)::bigint AS locations,
  count(*) FILTER (WHERE operational=0)::bigint AS no_operational,
  count(*) FILTER (WHERE free>0 AND unknown>0)::bigint AS free_and_unknown,
  count(*) FILTER (WHERE free=0 AND unknown>0)::bigint AS no_free_with_unknown,
  count(*) FILTER (WHERE free>0 AND unknown=0)::bigint AS free_no_unknown,
  count(*) FILTER (WHERE operational>0 AND free=0 AND unknown=0)::bigint AS no_free_known,
  count(*) = (
    count(*) FILTER (WHERE operational=0) +
    count(*) FILTER (WHERE free>0 AND unknown>0) +
    count(*) FILTER (WHERE free=0 AND unknown>0 AND operational>0) +
    count(*) FILTER (WHERE free>0 AND unknown=0) +
    count(*) FILTER (WHERE operational>0 AND free=0 AND unknown=0)
  ) AS location_partition_ok
FROM per_location;

ROLLBACK;
RESET ROLE;
SQL
```

## Interpretatie en grenzen

- **AVAILABLE + UNKNOWN**: ten minste één vrij is bekend, maar de onbekende capaciteit moet zichtbaar blijven.
- **Geen AVAILABLE + UNKNOWN**: **niet** als betrouwbaar 'allemaal bezet' presenteren.
- **Alle bekende niet-AVAILABLE, geen UNKNOWN**: 'geen vrij' is uit de gerapporteerde statussen afleidbaar, *mits* feed- en locatiedekking voldoende betrouwbaar zijn.
- **Nul operationele EVSE's**: niet rood behandelen als 'bezet'.
- PLANNED/REMOVED zijn uitgesloten; actieve presence is iets anders dan bronstatus.
- Bron-update-ouderdom is geen direct bewijs van feedstaleness; globale checkpoint-age is geen direct bewijs van actuele individuele status.

Verwacht `location_partition_ok=t` en de 204 operationele statusrijen uit Q5a (indien snapshot ongewijzigd). Verschillen na nieuwe NDW-import zijn mogelijk en hoeven geen testfout te zijn. Query is read-only en maakt geen API-, database- of Android-wijzigingen. PR #114 blijft draft; Q4c-04c/Q4c-08b open.


## Werkelijke NAS-resultaten — 3 november 2026

De gebruiker voerde beide read-only Q5b-queries daadwerkelijk uit:

| raw_status | evse_count | null_source_timestamp | youngest_source_age_min | oldest_source_age_min |
|---|---:|---:|---:|---:|
| AVAILABLE | 151 | 0 | 7,3 | 121937,3 |
| CHARGING | 48 | 0 | 6,5 | 8585,5 |
| UNKNOWN | 4 | 0 | 55,9 | 3802,2 |
| OUTOFORDER | 1 | 0 | 2548,4 | 2548,4 |

| locations | no_operational | free_and_unknown | no_free_with_unknown | free_no_unknown | no_free_known | location_partition_ok |
|---:|---:|---:|---:|---:|---:|---|
| 98 | 0 | 0 | 2 | 86 | 10 | t |

**Q5b PASS voor de status-/locatiepartitie.** De 204 operationele EVSE's zijn verdeeld in 151 AVAILABLE, 48 CHARGING, 4 UNKNOWN en 1 OUTOFORDER. Alle bronupdate-timestamps in deze sample zijn gevuld. Het oudste AVAILABLE-`source_last_updated` is ~84,7 dagen oud; de oudste CHARGING-bronupdate ~6,0 dagen, UNKNOWN ~2,6 dagen, OUTOFORDER ~1,8 dagen. Een oude statuswijziging bewijst **niet** automatisch dat de statuspublicatie/feed verouderd is. De feed-checkpoint en bronstatus-timestamp zijn verschillende signalen.

De locatieverdeling is **86** met minstens één bekende AVAILABLE en geen UNKNOWN, **10** zonder vrij maar volledig bekende operationele statussen, en **2** zonder vrij met UNKNOWN. Voor deze laatste twee mag een rode/volledige-bezet-classificatie niet betrouwbaar worden afgeleid. Dit betreft *statuszekerheid onder aanwezige actieve EVSE-records*, nog geen bewijs dat de NDW-bronlocatie volledig is of dat individuele statuswaarnemingen actueel zijn. Q5c moet bevestigings-/coverage-semantiek onderzoeken voor een definitief ringcontract. Geen mutaties, merge of deployment.
