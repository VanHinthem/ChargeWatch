# ChargeWatch: automatische databasemigraties

De service `migrate` in `docker-compose.yml` wacht totdat PostgreSQL gezond is. De eenmalige PostgreSQL 18-client voert `db/migrate.sh` uit. Dit script verwerkt `db/migrations/NNN_*.sql` op volgorde en slaat versies die al in `schema_migration` staan over.

Iedere migratie draait in één database-transactie, met een PostgreSQL advisory lock die gelijktijdige migrators serialiseert. Een fout stopt de migrator en draait de lopende transactie terug. Er wordt geen terugdraaiing uitgevoerd van **eerder succesvol toegepaste** migraties. Daarom blijft een databaseback-up verplicht vóór goedgekeurde productiemigraties.

De migrator gebruikt tijdelijk `chargewatch_owner` met de bestaande `CW_DB_OWNER_PASSWORD`. De collector behoudt zijn eigen importgebruiker en de API zijn read-only databasegebruiker.

**Belangrijke NAS-integratie:** in deze repository bevat `docker-compose.yml` uitsluitend de database en migrator; collector en API kunnen via een andere deployment lopen. Die nieuwe applicaties mogen pas starten nadat `migrate` succesvol is afgerond. Koppel de externe Compose-services via `depends_on: migrate: condition: service_completed_successfully` binnen dezelfde stack, of voer de migrator als verplichte eerste deploymentstap uit en controleer de exitcode.

De databasebootstrap onder `db/init` blijft nodig bij een helemaal lege database. De migrator verwacht `schema_migration` al aanwezig. Een gewone Compose-update start de migrator en verwerkt alleen ontbrekende versies. Dit betekent **niet** dat ChargeWatch automatisch naar productie mag worden uitgerold: de gebruiker blijft apart merge- en deploymentakkoord geven.

CI voert de migrator tweemaal uit op een aparte testdatabase en verifieert dat een SQL-fout zonder gedeeltelijke wijzigingen terugrolt. De echte migratie 014 wordt daarnaast op PostgreSQL 18 getest. Op de NAS is niets aangepast.
