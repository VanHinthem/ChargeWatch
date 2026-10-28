# Echte NDW-tarieven veilig valideren — issue #66

Status: read-only diagnostiek via draft PR #78, **geen productie-ingreep**. Synthetische EFL-fixtures in PR #77 zijn geen vervanging voor deze controle.

## 1. Eén echte EFL-OCPI-tariefpayload uitlezen op de NAS

Voer vanaf de ChargeWatch-repositoryroot op de NAS uit. De DB is niet via een hostpoort bereikbaar. Het commando gebruikt expliciet de **read-only** PostgreSQL-rol en selecteert één daadwerkelijk aanwezig EFL-tarief met minimaal 49 elementen, bij voorkeur een 48-uurspatroon. Bij geen match blijft het uitvoerbestand leeg: rapporteer dat, maak geen tarief na.

```sh
mkdir -p .local/tariff-validation
chmod 700 .local/tariff-validation
sudo docker compose -f deploy/nas/compose.yaml --env-file deploy/nas/.env exec -T db sh -c \
  'PGPASSWORD="$CW_READ_PASSWORD" psql -X -q -A -t -h 127.0.0.1 -U chargewatch_read -d chargewatch -v ON_ERROR_STOP=1 -c \
  "SELECT details::text FROM tariff WHERE country_code = '\''NL'\'' AND party_id = '\''EFL'\'' AND jsonb_typeof(details->'\''elements'\'') = '\''array'\'' AND jsonb_array_length(details->'\''elements'\'') >= 49 ORDER BY jsonb_array_length(details->'\''elements'\'') DESC, id LIMIT 1"' \
  > .local/tariff-validation/efl-real.json
chmod 600 .local/tariff-validation/efl-real.json
```

**Veiligheid:** het bestand bevat originele tarieven en kan CPO-metadata bevatten; niet committen, uploaden of publiek delen zonder gecontroleerde anonimisering. `.local/` moet in `.gitignore` staan (controleer vóór git add). Het commando doet uitsluitend SELECT.

## 2. Offline evaluator uitvoeren

Op een machine met de .NET 10 SDK en de branch met deze PR:

```sh
dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- \
  --validate-tariff-file .local/tariff-validation/efl-real.json Europe/Amsterdam 2026-10-28T12:30:00Z
```

Het commando start geen webhost en maakt geen verbinding met de database. Output is **alleen een diagnostische JSON-samenvatting**: aantal elementen, gekozen dimensies, elementindex, toepasselijkheid, prijscomponenten, metadata en beperkingen. Geen tariff IDs, ruwe payload of alternatieve tekst in de output.

Test verschillende UTC-momenten, inclusief overgang naar een ander uur, na afloop van de 48-uursperiode en waar van toepassing zomertijd-/wintertijdovergangen. Gebruik de werkelijke OCPI-locatietijdzone; `Europe/Amsterdam` is uitsluitend passend voor een geverifieerde Nederlandse locatie.

## 3. Acceptatie

- Controleer dat de eerste toepasselijke ENERGY-regel geselecteerd wordt en dat de onbeperkte fallback niet dubbel bij ENERGY wordt geteld.
- Beoordeel afwijkende velden/restricties afzonderlijk; deel geen gevoelige of uitgebreide landelijke brondata in Git.
- Registreer aantallen, status en eventuele afwijkingen in issue #66. **Pas na succesvolle authentieke bronvalidatie** volgen de DB-join en het additieve API-contract.
- Bij ontbrekende EFL-tariefreferenties valideer eerst dat het bronobject bestaat; geen ander vergelijkbaar tarief substitueren.

Dit is een handmatige, read-only verificatiestap: CI kan geen toegang tot de privé-NAS aantonen.

### Offline validatie van parkeerduur (PR #78)

Op de Windows-ontwikkelmachine met de originele export lokaal opgeslagen (geen bronrepository op de NAS nodig):

```powershell
$tariff = "C:\GITHUB\ChargeWatch\.local\tariff-validation\efl-real.json"
foreach ($seconds in 3599, 3600) {
    dotnet run --project src/ChargeWatch.Api/ChargeWatch.Api.csproj -- --validate-tariff-file $tariff Europe/Amsterdam 2026-09-03T10:30:00Z "--duration-seconds=$seconds"
}
```

`--duration-seconds=N` is optioneel, vereist een niet-negatief geheel aantal seconden en kan na de tijd of na de optionele gevraagde tariff type staan. De diagnostische uitvoer bevat `sessionDurationSeconds`. Verwacht op EFL-tarief #29326: 3599 seconden -> PARKING_TIME 0 uit element 49; 3600 seconden -> PARKING_TIME 6 uit element 50; elk exact één toepasselijke parkeercomponent, zonder `SESSION_CONTEXT_REQUIRED`. De waarde 6 is een **prijscomponent**, geen berekend parkeertotaal. Voor dit tarief is de bron-`step_size` 1. De gedocumenteerde test verandert geen database en toont geen ruwe bronpayload.

Let op: `dotnet run --project` kan de werkdirectory veranderen naar de projectdirectory; gebruik op Windows voor het JSON-bestand een **absoluut pad**.
