# PR #67 lokale integratietest — 27 oktober 2026

Uitgevoerd door gebruiker op lokale Docker PostgreSQL 18/PostGIS container `chargewatch-s4test-db-1`, volledig gescheiden van de NAS. Bronbestanden: NDW Locations en Tariffs van 27 oktober 2026. Importer en API .NET 10 builds succesvol; test Docker image `chargewatch-pr67-test:local` succesvol gebouwd.

| Stap | Uitkomst |
|---|---|
| Na migratie 015, beginstatus | 82.226 totaal, 82.226 actief, 0 inactief |
| Handmatige `--bulk`, bron 80.925 locaties | commit succesvol in 32,4 s; 82.836 totaal/actief, 0 inactief |
| HTTP 200 `--watch-once`, bron 80.925 locaties | commit succesvol in 28,7 s; 82.836 totaal, 80.925 actief, 1.911 inactief |
| HTTP 200 `--watch-once`, zelfde bron plus één geldige testlocatie | commit succesvol in 29,9 s; 82.836 totaal, 80.926 actief, 1.910 inactief |

Reactivatie-identiteit `(NL,505,91106154)` kwam terug als bestaand DB-ID **134**, met vóór en na beide **25 EVSE's en 25 connectoren**. Testlocatie was afwezig in actuele NDW-export en werd via gescheiden lokaal test-gzip-bestand met 80.926 records aangeboden.

**Aangetoond:** handmatige bulk deactiveert niet; geverifieerde volledige HTTP-200-snapshot deactiveert ontbrekende locaties; herpublicatie reactiveert bestaande identiteit met behoud van ID en gekoppelde rijen.

**Nog niet aangetoond op dat moment:** historische statistiekwaarden vóór/na, fault/rollback- en gedeeltelijke-snapshotbescherming, CI-automatisering, zoekendpoint en performancebenchmark. Tests waren handmatige lokale runs, geen geautomatiseerde CI-tests. Geen merge of productie-deployment.

## API-verificatie en gekoppelde records

Gebruiker bouwde de API-image lokaal en draaide deze via het read-only account `chargewatch_read` op poort 18080. Resultaten: actief locatie-ID 134 `GET /api/v1/locations/134` **200**; inactief ID 139 `GET /api/v1/locations/139` **404**; NDW-lookup `NL-505-91106214` **404**. Viewport rondom inactieve locatie (bbox `4.732559,52.710611,4.742559,52.720611`) bevatte zes locaties, **nul** vermeldingen van `NL-505-91106214`.

Bij 1.910 inactieve locaties bleven 7.397 EVSE's en 7.397 connectoren gekoppeld. Historische locatie-accumulator bevatte 106 rijen, maar geen enkele rij was gekoppeld aan de inactieve locaties. Beide `location_snapshot_*`-historietabellen hadden nul rijen. Dit bewijst geen dataretentie bij inactivering van een locatie mét historie: expliciete regressietest volgt. Rollback-faultinjectie en zoekendpoint staan eveneens nog open.

## Historietest en bewuste testbeperking

Actieve locatie `3791` / `NL-EVT-NLSHOE00025346` had **16** rijen in `location_hour_accumulator` vóór de gecontroleerde import. Een lokale volledige test-snapshot met 80.925 locaties, zonder deze locatie, werd succesvol gecommit (203.507 EVSE's, 204.209 connectoren, 45,3 seconden). Daarna: locatie **3791 inactief**, 16 historische records nog aanwezig, checksum na import `63e200cc544c90315bcfbf8561024dff`. De checksum van **vóór** de import is niet in de teruggekoppelde resultaten opgenomen; byte-voor-byte gelijkheid is dus **niet bevestigd**. De SQL van de reconciliatie wijzigt alleen `charging_location.is_active`, niet de historietabellen.

De gebruiker heeft op 27 oktober 2026 expliciet gekozen **de rollback-faultinjectietest over te slaan** en verder te gaan. Dit is een bewuste acceptatie van resterend test-risico, **niet** een geslaagde rollback-test. Ook een geautomatiseerde regressietestsuite, beperkte/onverwacht veranderde volledige snapshot en een losse zoekendpoint-test zijn niet uitgevoerd. De bestaande database-transactie ondersteunt rollback, maar die specifieke foutconditie is in deze PR niet empirisch gevalideerd.

### Korte testconclusie

Handmatige reële integratie: **geslaagd** voor toevoegen, deactiveren, reactiveren, behoud van IDs/EVSEs/connectors, details/NDW-lookup en kaartviewport. Historie-rijen bij deactivering bleven aanwezig. Geen productie-NAS-aansluiting, geen merge of deployment uitgevoerd tijdens deze tests. Voor toekomstig onderhoud blijven geautomatiseerde database-regressietests wenselijk.
