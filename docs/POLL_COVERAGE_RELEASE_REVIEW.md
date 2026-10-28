# Issue #72 — release review / go-no-go

Status: **niet naar productie uitrollen zonder expliciet akkoord**. Dit is een voorbereidingschecklist voor PR #83.

## Bevestigd in CI

- Echte `SnapshotPollCoverage.Capture` test op PostgreSQL: normale intervallen, replay, statuswisseling, subsecond-uurgrens, behoud van legacy-records.
- Migratie 020: nullable BIGINT-microseconden, oude secondekolommen blijven. `NOT VALID` voorkomt een onmiddellijke bestaande-rijenvalidatiescan maar controleert nieuwe/gewijzigde rijen.
- Synthetische kandidaatbenchmark met 205.000 rijen: 1.369 ms voor twee projecties versus 744 ms voor één materialisatie + twee hergebruiken; deze vergelijking is **geen** NAS end-to-end snelheidsmeting.
- Laatste green check: PR #83 commit `25ce5e41`, tien checks success.

## Nog nodig voor GO

1. Code review van de definitieve diff en SQL-invarianten, inclusief het feit dat het resultaat van een kandidaatinterval met bestaande legacy-uurdekking niet wordt toegevoegd en toch het broncheckpoint doorloopt. Dat is een bewuste keuze: nooit kunstmatige precisie creëren.
2. Confirmatie dat geen consument van `covered_seconds` vereist dat een waarde van 0 ook een echt leeg interval betekent. Nieuwe microsecondevelden kunnen >0 zijn terwijl legacy seconden 0 zijn.
3. Verifieer dat de NAS-release altijd dezelfde `CW_IMAGE_TAG` voor DB-migrator, collector en API gebruikt en dat de oude collector geheel gestopt is vóór migratie 020; niet afzonderlijk de oude collector laten doorlopen.
4. Release via bestaande UGREEN Docker-procedure met verplichte pre-migration custom-format PostgreSQL-backup en `pg_restore --list`-controle. Dit is geen bewijs van volledige restore; waar mogelijk geïsoleerde restore uitvoeren. Geen handmatige productie-SQL om migratie te versnellen.
5. Na uitrol: controleer upgradejob healthy, collector/API healthy, migratie 020 aanwezig; eerste nieuwe uurrecord met `covered_microseconds IS NOT NULL`, beide microsecondewaarden binnen 0–3600000000 en `available_microseconds<=covered_microseconds`. Verwacht legacy-uren met NULL-velden; geen backfill.
6. Vergelijk minuten per volledige collector-import vóór/na, waarschuwingen exact onderscheiden (legacy skipped versus true overflow); monitor minstens de eerste volledige UTC-uurgrens. Stop bij herhaalde errors of langdurige importvertraging.

## Tijdelijke SQL-verificatie (read-only, via chargewatch_read)

```sql
SELECT version FROM schema_migration
WHERE version='020_poll_coverage_precision';

SELECT
    count(*) FILTER (WHERE covered_microseconds IS NULL) AS legacy_rows,
    count(*) FILTER (WHERE covered_microseconds IS NOT NULL) AS precise_rows,
    count(*) FILTER (WHERE covered_microseconds IS NOT NULL
      AND (covered_microseconds NOT BETWEEN 0 AND 3600000000
        OR available_microseconds NOT BETWEEN 0 AND covered_microseconds)) AS invalid_precise
FROM evse_poll_hour_coverage
WHERE hour_start_utc >= date_trunc('hour',now()) - interval '2 hours';
```

> De productiecontrole is pas zinvol **na goedgekeurde uitrol** en mag geen enkele schrijf- of herstelactie bevatten.

## Geen claim

De precieze oorzaak van elk van de historische 123.421 onderdrukte increments is niet gereconstrueerd. De oude dekking blijft een geaggregeerde legacyreeks zonder exact intervalverloop. PR #83 verhelpt aangetoonde cumulatieve afrondingsfouten voor nieuwe exacte uurrecords; de PR garandeert niet dat alle toekomstige warnings of echte overlappen verdwijnen.
