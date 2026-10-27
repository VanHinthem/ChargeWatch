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

**Nog niet aangetoond:** daadwerkelijke API-endpoints, historische statistiekwaarden vóór/na, fault/rollback- en gedeeltelijke-snapshotbescherming, CI-automatisering en performancebenchmark. Tests waren handmatige lokale runs, geen geautomatiseerde CI-tests. Geen merge of productie-deployment.
