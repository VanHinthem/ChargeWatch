# ChargeWatch — batchdetails voor favorieten en widgets

**Ontwerpdatum:** 5 november 2026  
**Status:** API-implementatie in afzonderlijke draft-PR; eerst API-acceptatie, daarna Android-aansluiting in PR #123. Geen productie-/NAS-deployment in deze PR.

## Endpoint

`POST /api/v1/locations/by-ndw-id/batch` op dezelfde read-only API-host als bestaande `GET /api/v1/locations/by-ndw-id/{featureId}`.

```json
{
  "featureIds": ["NL-TST-PRESENCE", "NL-NUO-0bf8edee-f912-4891-8b4c-ad709ff523fb"],
  "includeStatistics": true
}
```

- `featureIds`: 1 t/m **20** unieke, syntactisch geldige externe NDW GeoJSON-ID's; gemengde of ongeldige input resulteert in HTTP 400. Geen favorietennamen of aliassen in het verzoek. De server slaat deze lijst niet op.
- `includeStatistics`: optioneel, default **true** voor de toekomstige uitgebreide favorietensheet/widget. Bij frequente beschikbaarheidsverversing kan de client `false` kiezen; dit laat het contract intact zonder telkens tijdreeksen uit te sturen.
- Response is een array **in verzoekvolgorde** met per `featureId` status `FOUND` of `NOT_FOUND`, de volledige `LocationDetail` als `detail` (of null) en optionele `LocationStatistics` als `statistics` (null wanneer niet gevraagd of niet gevonden). Een missende locatie blokkeert andere locaties niet.

```json
{
  "locations": [
    {"featureId":"NL-TST-PRESENCE","status":"FOUND",
     "detail":{"id":42,"countryCode":"NL","partyId":"TST","sourceId":"PRESENCE",
                "address":"CI Presence","evses":[]},
     "statistics":{"locationId":42,"slots":[],"evses":[],
                   "isExperimental":true,
                   "probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA"}},
    {"featureId":"NL-TST-MISSING","status":"NOT_FOUND",
     "detail":null,"statistics":null}
  ]
}
```

Het voorbeeld is schematisch: detailobjecten bevatten ook de bestaande optionele velden, EVSE-/connector-/tariefopties en tijdstempels. Reële integratietests vergelijken de veldcontracten met de individuele `GET`-endpoints.

## Database en veiligheid

- Eén geïndexeerde set-lookup voor de aangeleverde samengestelde OCPI-identiteiten (country, party, source), niet één SELECT per favoriet.
- Eén query voor de **volledige** EVSE/connector/tariefdetails van de gevonden locaties; identieke tarief-evaluatie als in de individuele detail-API (waaronder onbekende of ontbrekende tarieven).
- Bij `includeStatistics=true` twee gezamenlijke queries naar de compact opgeslagen locatie- en EVSE-historiek; geen historiequery bij `false`.
- Feeds/tariefcheckpoint elk eenmaal opvragen, resultaten in geheugen per locatie groeperen. Alleen `chargewatch_read` en uitsluitend SELECTs. Geen DB-migraties, collectorwijzigingen, nieuwe persoonsgegevens of opslag van favorieten.
- Bestaande routes en bestaande mobiele builds blijven compatibel; de batch-API is strikt additief. Dezelfde bestaande algemene API-rate-limit blijft gelden.
- Gegevensveroudering behouden de bestaande `isStale`, `feedIsStale` en `tariffsFeedIsStale`-semantiek; `isExperimental` bij statistieken is ongewijzigd.

## Integratievolgorde

1. API-PR CI: .NET build/self-test, geïsoleerde PostGIS HTTP-test voor 1/20 ID's, GET-equivalentie, niet-gevonden IDs en invoerfouten.
2. Na aparte toestemming API-PR squash-mergen; publiceren en **NAS API deplooyen** is een afzonderlijke handeling.
3. **Pas daarna** Android PR #123 opnemen/testen: favorieten en widget via gedeelde batch-cache in plaats van geografische viewportrequests, selectie via reeds opgehaalde details en historie. Oude app blijft werken.
4. Daarna Samsung-acceptatie en pas dan Android-PR mergen. Statistieken voor compacte kaartsheets blijven lazy en staan los van favorieten.

## Grenzen

Maximaal 20 aangeleverde ID's per verzoek is een **API-limiet**, geen garantie dat lokaal opgeslagen favorieten boven dit aantal worden verwijderd. De afzonderlijke Android-PR #123 beperkt nieuwe opgeslagen favorieten tot 20, maar verwijdert bestaande lijsten met meer favorieten niet; zulke lijsten worden per maximaal 20 IDs in een of meer batches verwerkt.
