# Sprint 0 — NDW OCPI data discovery

**Datum:** 22 oktober 2026  
**Issue:** #2  
**Status:** in uitvoering; broncontract en publicatiemetadata geverifieerd, raw landelijke OCPI-payload nog niet lokaal geëxtraheerd.

## Doel

Valideer de landelijke NDW/DOT-NL OCPI Locations- en Tariffs-feed voordat ChargeWatch een database- of importercontract vastlegt.

## Gecontroleerde bronnen

- NDW Open Data: `https://opendata.ndw.nu/`
- Locations: `https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz`
- Tariffs: `https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz`
- NDW consumerdocumentatie: `https://docs.ndw.nu/en/data-uitwisseling/interface-beschrijvingen/dafne-api/dafne_api_consumer_pull/`
- NDW supplierdocumentatie: `https://docs.ndw.nu/data-uitwisseling/interface-beschrijvingen/dafne-api/dafne_api_supplier/`

## Feitelijke bevindingen

### Publicatie

Op 22 oktober 2026 rond 12:15 lokale tijd meldde het NDW Open Data-portaal:

| Dataset | Laatst gewijzigd | Gecomprimeerde grootte |
|---|---:|---:|
| OCPI Locations | 2026-10-22 12:15 | circa 17 MB |
| OCPI Tariffs | 2026-10-22 12:02 | circa 4.0 MB |
| GeoJSON locaties | 2026-10-22 12:15 | circa 4.7 MB |

Dit laat zien dat de publieke exports meerdere keren per dag verversen. Het exacte productie-interval en HTTP cachinggedrag moeten nog gemeten worden; niet hardcoderen op basis van deze ene observatie.

### Contract en stabiliteit

- DOT-NL levert kosteloos actuele informatie over publiek toegankelijke laadpunten.
- De landelijke Locations- en Tariffs-bestanden zijn downloadbare datasets, geen normale resource-API's. NDW documenteert expliciet een `curl ... | gunzip`-afname.
- Leveranciers leveren momenteel OCPI **2.2.1**.
- NDW waarschuwt dat de laadpunten-API's/specs nog in ontwikkeling zijn en kunnen wijzigen.
- Voor correcte herkomst is `party_id` de party van de CPO; NDW zegt data zonder correcte CPO-party niet te ontsluiten.
- Naast standaard OCPI 2.2.1 maakt NDW/AFIR bij aanlevering deze velden verplicht:
  - Location: `opening_times`, `energy_mix`
  - EVSE: `parking_restrictions`
  - Connector: `max_electric_power`, `tariff_ids`
- NDW beschrijft dagelijkse pull van de volledige huidige set bij leveranciers plus pushwijzigingen binnen een minuut. Dat is de aanleverzijde; het verversingsgedrag van de publieke landelijke export moet afzonderlijk worden gemeten.

## Voorlopige ChargeWatch-mapping

Deze mapping is gebaseerd op OCPI 2.2.1 + de door NDW gedocumenteerde verplichte uitbreidingen. De precieze aanwezigheid/vulling in de landelijke export moet nog tegen echte records worden gecontroleerd.

| ChargeWatch | NDW/OCPI input | Opmerking |
|---|---|---|
| ChargingLocation source identity | `country_code + party_id + id` | Samengestelde natuurlijke bronsleutel; interne sleutel blijft apart. |
| ChargingLocation operator | `operator` en/of `party_id` | Fallbackgedrag pas vastleggen na payloadcontrole. |
| ChargingLocation adres | Location address/city/postal_code/country | Exacte nullability nog meten. |
| ChargingLocation coördinaten | `coordinates.latitude/longitude` | Opslaan als ruimtelijk punt. |
| ChargingLocation opening | `opening_times` | Door NDW verplicht bij aanlevering. |
| ChargingLocation energiemix | `energy_mix` | Door NDW verplicht bij aanlevering. |
| Evse source identity | Location-key + EVSE `uid` | `evse_id` ook bewaren als externe identifier waar aanwezig. |
| Evse status | `status` | Mapping naar vrij/niet-vrij/defect/onbekend pas definitief na waargenomen statusset. |
| Evse statusmoment | EVSE `last_updated` | Te valideren als bruikbare versheidsbron. |
| Evse parkeerrestricties | `parking_restrictions` | Door NDW verplicht bij aanlevering. |
| Connector identity | EVSE-key + connector `id` | Bron-ID binnen EVSE. |
| Connector type | `standard`, `format`, `power_type` | OCPI-semantiek behouden. |
| Connector vermogen | `max_electric_power` | Primair indien gevuld; spanning/stroom eventueel als fallback na validatie. |
| Connector tariff link | `tariff_ids[]` | Moet tegen Tariffs-feed resolveren; dekking expliciet meten. |
| Tariff source identity | `country_code + party_id + id` | Exacte exportvorm nog tegen raw payload controleren. |
| Tariff prijs | `elements[].price_components[]` | Energie/tijd/parking/flat niet tot één kWh-prijs reduceren. |
| Tariff geldigheid | tariff restrictions / start/end | Exacte gebruikte velden na payloadinspectie vastleggen. |

## Schietspoel 18 referentie

Reeds in de projectdocumentatie vastgelegde referentie voor vergelijking:

- locatie-id uit Athlon/Deftpower: `f08bed59-be4b-42d4-8b9b-423f03f82bc6`
- NDW GeoJSON-id: `NL-NUO-f08bed59-be4b-42d4-8b9b-423f03f82bc6`
- EVSE's: `NL*NUO*EALF*0005002*1` en `NL*NUO*EALF*0005002*2`
- locatie: Schietspoel 18, Oss
- eerder waargenomen: twee Type-2 AC EVSE's rond 11 kW

Dit is de zoekanker voor de raw OCPI-extractie. De huidige Sprint-0-branch claimt **nog niet** dat de landelijke OCPI-feed deze velden vandaag exact zo bevat.

## Datakwaliteit die al relevant is

Een onafhankelijke analyse van de NDW-feed (snapshot 7 oktober 2026) rapporteerde 74.982 locaties, 182.175 actieve publieke laadpunten en 99.230 tarieven. Daarbij resolveerde niet ieder laadpunt naar een tarief en was status niet altijd bekend. Deze tellingen zijn bruikbare sanity checks, geen vervanging voor onze eigen snapshotmeting.

Daarom moet de importer:
- ontbrekende tariff-links niet als prijs 0 behandelen;
- `UNKNOWN`, `REMOVED` en `PLANNED` expliciet onderscheiden;
- source freshness apart bewaren;
- een onvolledige/mislukte landelijke import nooit als verwijdering of statusovergang interpreteren.

## Nog te bewijzen met de echte gzip-payload

Voor afronding van issue #2 ontbreken nog:

1. Top-level JSON-vorm van beide landelijke exports.
2. Exact Schietspoel 18 Location-object en de twee EVSE-objecten uit dezelfde snapshot.
3. Complete waargenomen EVSE-statusset en frequenties.
4. De `tariff_ids` van Schietspoel-connectors en resolutie naar concrete Tariff-objecten.
5. Voorbeeld van prijscomponenten/restricties uit een daadwerkelijk gekoppeld tarief.
6. Aantallen locations/EVSE's/connectors/tariffs in dezelfde snapshot.
7. HTTP metadata: `ETag`, `Last-Modified`, `Content-Length` en gedrag van conditionele requests.
8. Kleine raw fixtures uit die snapshot, met alleen representatieve records en zonder credentials.
9. Licentie/attributievoorwaarden voor herpublicatie in app/API expliciet vastleggen.

## Toolingbeperking van deze run

De webomgeving kon de Open Data-index en documentatie live lezen, maar kon de 17 MB gzip niet als payload materialiseren: de webfetch wees het bestand af wegens omvang en de container heeft geen externe DNS/netwerktoegang. Daarom zijn er bewust **geen synthetische raw fixtures** toegevoegd.

Dit is een uitvoeringsbeperking van deze agentrun, niet een NDW-beperking: NDW documenteert de download expliciet als publiek en zonder credentials.

## Volgende actie

Voer de raw gzip-extractie uit in een omgeving met directe HTTP-download, leg de Schietspoel-records plus gekoppelde tariff(s) als kleine fixtures vast, maak een compact analyse-script/check en rond daarna deze feature af. Pas daarna schema/performancewerk starten.
