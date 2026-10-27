# ChargeWatch — audit volledigheid NDW/OCPI-gegevens

Status: onderzoeksbasis (27 oktober 2026). **Geen productiedatabase of live OCPI-snapshot uitgelezen.** De onderstaande codebevindingen zijn vastgesteld op `main` en moeten met echte data worden gevalideerd. Dit onderzoek staat los van Android UX PR #61.

## Doel

Meet welke gegevens NDW OCPI 2.2.1 werkelijk levert, welke velden ChargeWatch importeert, bewaart, aanbiedt en toont, en waar betekenis of precisie verloren gaat. Behoud broninformatie; presenteer interpreteerbare gegevens correct en toon onbekende/ongeldige waarden zonder te gokken.

## Bevindingen uit het huidige broncontract

| Gegeven | NDW / OCPI | Database / API | Android | Risico / vervolg |
|---|---|---|---|---|
| EVSE identiteit/status | EVSE uid, evse_id, status | `evse.source_uid`, `external_evse_id`, status; detail API met EVSE-array | Samengevat per kaartlocatie | EVSE versus connector versus laadplaats duidelijk houden |
| Connectorstandaard | OCPI `standard` / NDW `connector_type` | `connector.standard`, API-detail + kaartcategorie | Beperkte bekende soorten, anders 'Overige aansluiting' | Werkelijk aanwezige standaardcodes inventariseren; onbekende code niet stilzwijgend weggooien |
| Connectorformaat | OCPI `format` / NDW `connector_format` | Niet aanwezig in `db/init/001_schema.sql` of API ConnectorDetail | `NdwAvailability.connectorFormat` aanwezig maar niet weergegeven | Socket versus vaste kabel mogelijk verloren via importer/backend |
| Vermogen | OCPI `max_electric_power`; NDW `power_max` in W | `connector.max_electric_power`, kaart `powerMax` | Watt naar kW met afronding | Ontbrekende/afwijkende vermogens en W-eenheden meten; geen daadwerkelijk laadsnelheid claimen |
| Elektrische details | OCPI `max_voltage`, `max_amperage`, `power_type` | Alleen `power_type` en max vermogen in init-schema | AC/DC slechts in uitgebreide connectorlijst | Bepalen of spanning/stroom bruikbaar en aanwezig zijn |
| EVSE-telling | Connectoren kunnen dezelfde EVSE delen | GetMapViewport telt unieke EVSE's op locatieniveau en per categorie | Kaart toont ratio, categorieën naast elkaar | Per categorie mogen EVSE's overlappen; geen categorie-totalen optellen |
| Tariefrelaties | `tariff_ids` per connector | `connector_tariff` bewaart relatie | Parser groepeert prijscomponenten voor compacte weergave | Meerdere tarieven aan correcte connector/conditie koppelen |
| Prijscomponenten | ENERGY/TIME/PARKING_TIME/FLAT, btw, step size | `tariff.details` als JSONB, API `presentation` | Ondersteunde componenten, btw-berekening | Geen all-in prijs of ongegronde toewijzing |
| Restricties | `elements[].restrictions` | API `TariffElementPresentation.Restrictions` bevat JSON | Vooral `hasRestrictions` boolean | Betekenis (tijd/dag/hoeveelheid/vermogen) gaat verloren in UI |
| Overige tariefmetadata | Bijv. alt_text, min/max prijs, tariefvaliditeit | `tariff.details` mogelijk bronvelden; exacte werkelijke dekking onbekend | Niet systematisch verwerkt | Inventarisatie `details`-sleutels, niet aannemen dat ze aanwezig zijn |
| Locatie/EVSE-extra's | OCPI opening_times, energy_mix, parking_restrictions | Niet in huidige init-schema- en detailmodellen | Niet aanwezig | Bron, importer en behoefte afzonderlijk onderzoeken |

**Let op:** het schema hierboven is het init-schema. Controleer alle latere migraties en collector-mappings voordat een definitieve uitspraak over productieschema of dataverlies wordt gedaan.

## Prioritaire read-only onderzoeken op NAS/PostgreSQL

Gebruik uitsluitend een **read-only databaseverbinding** en transacties zonder mutaties, bij voorkeur rol `chargewatch_read`; geen exports van volledige tabellen, persoonsgegevens of credentials. Onderstaande queries geven uitsluitend aggregaten.

### A. Waargenomen connectorstandaarden en vermogens

```sql
BEGIN TRANSACTION READ ONLY;
SELECT coalesce(standard, '<NULL>') AS standard,
       coalesce(power_type, '<NULL>') AS power_type,
       count(*) AS connectors,
       count(*) FILTER (WHERE max_electric_power IS NULL OR max_electric_power <= 0) AS without_valid_power,
       min(max_electric_power) AS min_power_w,
       max(max_electric_power) AS max_power_w
FROM connector
GROUP BY standard, power_type
ORDER BY connectors DESC, standard, power_type;
COMMIT;
```

### B. EVSE's met meer dan één connector

```sql
BEGIN TRANSACTION READ ONLY;
SELECT connector_count, count(*) AS evses
FROM (
    SELECT e.id, count(c.id) AS connector_count
    FROM evse e LEFT JOIN connector c ON c.evse_id = e.id
    GROUP BY e.id
) x
GROUP BY connector_count
ORDER BY connector_count;
COMMIT;
```

### C. Beschikbare tariefcomponenten en restricties

```sql
BEGIN TRANSACTION READ ONLY;
SELECT component->>'type' AS component_type, count(*) AS occurrences
FROM tariff t
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(t.details->'elements') = 'array'
         THEN t.details->'elements' ELSE '[]'::jsonb END
) element
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(element->'price_components') = 'array'
         THEN element->'price_components' ELSE '[]'::jsonb END
) component
GROUP BY component->>'type'
ORDER BY occurrences DESC;
SELECT restriction.key, count(*) AS occurrences
FROM tariff t
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(t.details->'elements') = 'array'
         THEN t.details->'elements' ELSE '[]'::jsonb END
) element
CROSS JOIN LATERAL jsonb_each(
    CASE WHEN jsonb_typeof(element->'restrictions') = 'object'
         THEN element->'restrictions' ELSE '{}'::jsonb END
) AS restriction(key,value)
WHERE restriction.value <> 'null'::jsonb
GROUP BY restriction.key
ORDER BY occurrences DESC;
COMMIT;
```

### D. Overige tariefvelden en koppelingen

```sql
BEGIN TRANSACTION READ ONLY;
SELECT key, count(*) AS tariffs
FROM tariff t CROSS JOIN LATERAL jsonb_object_keys(t.details) AS key
GROUP BY key ORDER BY tariffs DESC;
SELECT count(*) AS connectors,
       count(*) FILTER (WHERE NOT EXISTS
           (SELECT 1 FROM connector_tariff ct WHERE ct.connector_id=c.id))
           AS connectors_without_tariff_relation
FROM connector c;
SELECT count(*) AS tariff_references,
       count(*) FILTER (WHERE t.id IS NULL) AS unresolved_tariff_references
FROM connector_tariff ct LEFT JOIN tariff t
ON t.country_code=ct.tariff_country_code
AND t.party_id=ct.tariff_party_id
AND t.source_id=ct.tariff_source_id;
COMMIT;
```

## Te inspecteren bronbestanden

- `db/init/001_schema.sql` en `db/migrations/` — werkelijk schema (inclusief updates).
- Collector/importer onder `src/` — concrete mapping voor location, EVSE, connector en tariff.
- `src/ChargeWatch.Api/ApiDatabase.cs`, `ApiModels.cs`, `OcpiTariffPresentation.cs`.
- `android/app/src/main/java/nl/vanhinthem/chargewatch/data/ndw/NdwModels.kt`, `NdwGeoJsonParser.kt`, `ChargeWatchMapClient.kt`, `ConnectorPresentation.kt`.
- `android/app/src/main/java/nl/vanhinthem/chargewatch/data/enrichment/LocationEnrichmentClient.kt`.
- Kleine gecontroleerde OCPI-fixtures voor relevante situaties, niet volledige feeds committen.

## Acceptatie / volgende beslissingen

1. Leg per veld vast: aanwezig in bron, werkelijk gevuld (%), opgeslagen, API beschikbaar, Android geïnterpreteerd, onbekend/ongeldig.
2. Documenteer echte frequenties, zoals standaardcodes, vermogensklassen, meervoudige EVSE-connectoren, tariefcombinaties en restricties.
3. Bepaal of extra OCPI-informatie in aparte gestructureerde velden moet komen of als oorspronkelijke JSON bewaard moet blijven. Leg opslag-/migratiegevolgen vast vóór implementatie.
4. Scheid exacte, afgeleide, geschatte en onbekende gegevens. Één connector is niet automatisch één afzonderlijk laadpunt.
5. Maak pas daarna kleine implementatie-PR's: bronbehoud → rijker API-contract → Android-weergave en testen.
6. Geen wijzigingen aan productie, geen database-migraties en geen merge zonder reviewakkoord.

## Productieonderzoek: bevindingen 27 oktober 2026

De volgende cijfers zijn verkregen met read-only SQL op de NAS en gelden voor de onderzochte momentopname.

- Connectoren: 211.840. Hiervan 142.400 met vermogen tussen 1 en 500 kW, 69.413 zonder vermogen, 10 met nulwaarde, 6 tussen 500 en 1.000 kW en 11 boven 1.000 kW.
- Uitschieters boven 500 kW: TotalEnergies 2 maal 120.000.000 W; Eneco 8 maal 7.360.000 W; Milence 1 maal 1.200.000 W; Fastned 4 maal 600.000 W; E-Flux 2 maal 500.400 W. Mogelijke bron- of eenheidsfouten vereisen verificatie. Hoge DC-vermogens zijn niet automatisch fout.
- EVSE-verdeling: 210.437 met een connector, 676 met twee, 17 met drie. Totaal 211.130 EVSE's. Het tellen van unieke EVSE's blijft essentieel.
- Veelvoorkomende tariefrestricties: start- en eindtijd circa 37 duizend voorkomens elk; startdatum 32.145; einddatum 28.619; weekdagen 9.094; minimumduur 3.486; maximumduur 2.817. Tellingen zijn per tariefelement, niet per uniek tarief.
- Restrictiepatronen: 102.909 elementen zonder gevulde restricties; 27.392 met datum- en tijdvensters; 8.722 met weekdag en tijdvenster; 2.107 met minimum- en maximumduur.
- Voor 26-27 oktober: EFL heeft 498 tarieven met samen 11.952 ENERGY-componenten, PFG 7 tarieven met 63 ENERGY-componenten. Eén EFL-tarief bevat 48 tijdsgebonden uurprijzen en een afsluitend element zonder restricties met ENERGY 0,529 EUR/kWh, FLAT 0 en TIME 0; btw-percentages zijn niet ingevuld. Over alle EFL-tarieven bevatten 523 records precies 48 tijdgebonden elementen en één algemeen element; 24 andere bevatten 48 tijdgebonden en in totaal 51 elementen.
- Aanvullende metadata van dit EFL-tarief: geen alternatieve tarieftekst, URL, minimum- of maximumprijs, en geen algemene geldigheidsdatums. Dit beschrijft alleen dit ene voorbeeld.

### Normatieve interpretatie en ontwerpvoorwaarden

Volgens OCPI 2.2.1 Tariffs §11.3.1 is **volgorde bepalend binnen iedere afzonderlijke prijsdimensie**: selecteer het eerste element met een component van die dimensie waarvan de restricties voldoen. Een later onbeperkt element kan dienen als fallback. ENERGY, TIME, PARKING_TIME en FLAT worden apart beoordeeld en verschillende dimensies kunnen tegelijk van toepassing zijn. Dit betekent dat de onbeperkte ENERGY-component van het voorbeeld niet bij de toepasselijke uurprijs wordt opgeteld.

Bron: https://evroaming.org/wp-content/uploads/2024/11/OCPI-2.2.1-d2.pdf en https://ocpi.github.io/openapi-specification/ocpi/2.2.1/roles/cpo-interface.html

Voor toekomstige implementatie: bewaar OCPI-elementvolgorde en ruwe restricties, valideer locale tijdzone en daggrenzen, valideer aanvullende restricties per element, en selecteer prijscomponenten per dimensie. Controleer volledige connector-tariefkoppelingen, btw-status, feed-versheid en ontbrekende/ongeldige velden. Bereken nooit stilzwijgend een gegarandeerde laadpas-eindprijs. Bestaande Android-weergave uit PR #61 is niet geschikt om een reeks uurprijzen enkel als lijst van unieke bedragen samen te vatten.

**Status:** onderzoeksresultaten en ontwerpafspraken gedocumenteerd; nog geen tariefevaluator geïmplementeerd, geen wijziging aan productie, geen database-migratie en geen merge.


## NDW Ubitricity cross-feed controle — 27 oktober 2026

Read-only productiequery: 5.078 onopgeloste connector-tariefrelaties; 3.706 daarvan betreffen NL/UB2, verspreid over zes unieke tarief-ID's. Geen van de ontbrekende ID's werd in de lokale database onder een andere party gevonden.

Gebruiker downloadde **verse** `charging_point_tariffs_ocpi.json.gz` en `charging_point_locations_ocpi.json.gz` van NDW en zocht de exacte ID-strings in de gedecomprimeerde JSON. Voor alle zes UB2-ID's was het resultaat: **wel in Locations, niet in Tariffs**. Dit wijst sterk op een cross-feed referentiële inconsistentie in de bron; volledige structurele parsing en publicatiemomentvergelijking zijn nog nodig voor definitieve vaststelling.

| UB2 tarief-ID | Getroffen connectoren | Locaties met die ID |
| --- | ---: | ---: |
| `e5588c60d6992238baaa0200a90e838d` | 1852 | 920 |
| `58647f3817be3b2499cde83bf3b89246` | 1844 | 912 |
| `35af75bf8b996e114825dcb66e06c4d6` | 4 | 2 |
| `028760728f45ad70b3e343bb00399bd1` | 2 | 1 |
| `4473587a5b74614ccc3a0ccec240ea51` | 2 | 1 |
| `d9a8cf48d65c07cd86b3e8b9872ca1ac` | 2 | 1 |

Ontwerpconsequenties: rapporteer onderscheid tussen ontbrekende koppeling, onopgeloste bronverwijzing en interpreteerbaar tarief. **Nooit** prijs afleiden uit andere connectoren of ongeverifieerde ID-matches. Monitor bronconsistentie; overweeg NDW-issue met geanonimiseerde voorbeelden. De aantallen zijn een momentopname, geen live-garantie.

## Kruiscontrole EFL/NUO NDW-export — 27 oktober 2026

Op basis van 770 ontbrekende unieke tarief-ID's uit de database en vers gedownloade NDW Locations/Tariffs gzip-bestanden heeft de gebruiker een case-sensitive exacte-stringvergelijking uitgevoerd:

| Party | Tariffs-string | Locations-string | Aantal unieke IDs |
|---|---|---|---:|
| EFL | afwezig | aanwezig | 734 |
| EFL | aanwezig | aanwezig | 3 |
| EFL | afwezig | afwezig | 1 |
| NUO | afwezig | aanwezig | 32 |

766 van de 770 zijn uitsluitend als string in Locations gevonden. Dit is een sterke aanwijzing voor inconsistente bronverwijzingen, **nog geen gestructureerde OCPI-identiteitsvalidatie**. De drie EFL-ID's die in beide exports voorkomen, moeten als eerste met samengestelde OCPI-sleutel op collector-/importverlies worden onderzocht; de ene afwezige EFL-ID kan verouderd zijn. Geen automatische reparatie en geen inferentie van prijzen uit vergelijkbare aansluitingen.

## Verklaring EFL-versieverschil — 27 oktober 2026

De drie EFL-objecten die in de handmatig opgehaalde Tariffs-export wel aanwezig waren, ontbreken nog in de productiedatabase. Onderzoek van `WatchRunner.cs` toont standaard 5-minutencontrole voor Locations en 60-minutencontrole voor Tariffs; een nieuwe Locations-versie kan een bulkimport met de bestaande Tariffs-cache triggeren. `BulkImporter.cs` importeert de verstrekte tariefobjecten via staging en upsert.

NAS-status: Tariffs-cache `tariffs.json.gz` gewijzigd 27 oktober 05:20 UTC, state `LastModified` 05:01 UTC, laatste succesvolle tariefcontrole 10:33 UTC, ETag `"401503-65ecb5527e7f0"`. NDW HTTP HEAD kort daarna: nieuwe Tariffs-versie `Last-Modified: 11:02 UTC`, ETag `"4045e0-65ed060304060"`. De drie EFL-objecten in die nieuwere export hadden `last_updated` 09:56–11:00 UTC. De dataset van de collector liep dus achter op de handmatig verkregen snapshot. Verwachting: reguliere volgende tariefcontrole haalt de nieuwe export binnen; **niet nagecontroleerd op verzoek van gebruiker**. Er is geen collectorbug vastgesteld.

Vervolgontwerp: exposeer afzonderlijk per feed de laatste succesvolle controle, feitelijke contentversie (ETag/Last-Modified), succesvolle download en succesvolle import; `import_run` alleen is onvoldoende om Tariffs-actualiteit te beoordelen. Blijf NDW ontbrekende bronobjecten onderscheiden van tijdelijke snapshotverschillen. Geen productie-ingreep gedaan.

## Connectoren zonder tariefverwijzing — operatoranalyse (27 oktober 2026)

Read-only DB-snapshot bevat 27.401 connectoren zonder `connector_tariff`-relatie uit 211.851 connectoren. Grootste groepen: TotalEnergies 18.884/19.373 (97,5%), Vattenfall InCharge 4.940/53.892 (9,2%), operator onbekend 2.720/5.778 (47,1%), Laadnet 497/6.046. Alle TotalEnergies-connectoren in deze snapshot vallen onder OCPI `NL/GFX`.

Gebruiker heeft de vers gedownloade NDW Locations-export gestructureerd geparsed (location→EVSE→connector→`tariff_ids`) voor `NL/GFX`. NDW: 9.046 locaties, 19.087 connectoren, 487 mét en 18.600 zonder tarief-ID (97,4% ontbrekend). Productiedatabase: 19.373 connectoren, 489 mét en 18.884 zonder relatie (97,5% ontbrekend). Verschillende snapshots; bijna identieke percentages wijzen overtuigend op overwegend ontbrekende bronverwijzingen, niet op grootschalig importverlies. Geen per-connector volledige identiteit-reconciliatie uitgevoerd.

Vervolg: analyseer Vattenfall InCharge en de groep met ontbrekende operatornaam apart. UI: `Tarief niet beschikbaar` als er geen betrouwbare relatie bestaat, nooit prijs gokken.

## Vattenfall InCharge / NL-NUO: tariefverwijzingen (27 oktober 2026)

Productiedatabase: 53.892 connectoren, 4.940 zonder connector_tariff (9,2%) en 48.952 met relatie. Zelfstandig geparde huidige NDW Locations-export, partij NL/NUO: 22.229 locaties; 53.753 connectoren, 4.908 zonder tariff_ids (9,1%) en 48.845 met tariff_ids. Kleine snapshotverschillen en nagenoeg identieke percentages. Geen aanwijzing voor grootschalig verlies van links in collector; individuele identiteit-reconciliatie niet uitgevoerd. Dit staat los van 572 NUO connectorrelaties naar 32 ontbrekende tariefobjecten.

## Onderzoek verdwenen NL/AGO-locaties — 27 oktober 2026

Database bevat voor NL/AGO 1.025 locaties: 92 met operatornaam (bronupdate 26-27 okt) en 933 zonder operatornaam (bronupdate 16 juni–25 okt). De actuele NDW Locations-export bevat 92 NL/AGO-locaties met samen 280 connectoren, alle met operatornaam en tariff_ids. In de database bevatten de 933 AGO-locaties zonder operatornaam 2.593 connectoren, zonder tariefrelatie.

Vijf concrete NL/AGO source_id's uit de 933 oude records, allemaal afwezig in de actuele NDW Locations-export: 0811c848-427e-4896-b5ff-6698385d92b9; e4228f3f-7d5e-4921-a3df-9c13ab791b2b; 5b6aaffd-c4a0-4d26-914b-12118245817d; 2267f9f4-5d69-420b-8a68-05010117f9f0; b5098ea4-5ec9-4629-9545-30c7aae2b14f. Dit bevestigt verouderde productierecords voor deze vijf ID's. Nog niet alle 933 IDs individueel vergeleken.

In BulkImporter.cs is upsert van locaties en verwijdering van verouderde connector-tariefrelaties zichtbaar, maar geen expliciete verwijdering van locaties die niet meer in een volledige NDW-snapshot voorkomen. Mogelijk blijven verdwenen locaties zichtbaar en tellen ze mee. Vervolg: ontwerp veilige snapshot-reconciliatie (actief/inactief of tombstone), met behoud van historische observaties/statistieken; pas na volledige bron-ID-controle, schema-impactonderzoek en tests implementeren. Geen productiegegevens gewijzigd.

## Eindconclusie snapshot-reconciliatie — 27 oktober 2026

Exacte lokale vergelijking van de volledige productiedatabase-export versus NDW Locations-export op `(country_code,party_id,source_id)`: database **82.874**, NDW **80.925**, overlap **80.925**, alleen database **1.949**, alleen NDW **0**. Verdeling 1.949 uitsluitend in DB: NL/AGO 933; NL/505 (50five) 802; NL/GFX 93; NL/NUO 51; NL/ALL 16; NL/LMS 13; NL/EFL 12; NL/LNT 10; NL/QWC 5; NL/Q8E 4; NL/ENE 3; NL/CPI 2; NL/IPC 2; NL/LDL 1; NL/DAE 1; NL/LEV 1. Alle 80.925 locaties uit NDW aanwezig, maar de importer verwijdert of deactiveert niet meer gepubliceerde locaties niet. De vergelijking geldt voor de onderzochte exports/snapshots.

**Besluit:** geen verdere per-operator forensische analyse nodig; afzonderlijk implementatie-issue **#63**: https://github.com/VanHinthem/ChargeWatch/issues/63. Voorgesteld: veilige, transactionele snapshot-reconciliatie met actief/inactief-status, heractivatie bij terugkeer, uitsluiting van inactieven uit actuele API/kaart en behoud van historische statistieken. PR #62 blijft alleen onderzoeksdocumentatie; geen productie-implementatie en geen merge.
