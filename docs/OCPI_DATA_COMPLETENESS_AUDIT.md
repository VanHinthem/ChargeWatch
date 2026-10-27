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
