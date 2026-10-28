# OCPI 2.2.1-tariefevaluator — technisch ontwerp (onderzoeksfase)

Status: ontwerpvoorstel in PR #62, **niet geïmplementeerd**. Eerst review, daarna afzonderlijke implementatie-PR. Uitgangspunt: NDW OCPI 2.2.1; bron: https://evroaming.org/wp-content/uploads/2024/11/OCPI-2.2.1-d2.pdf (tariff module §11.3-11.4) en https://ocpi.github.io/openapi-specification/ocpi/2.2.1/roles/cpo-interface.html.

## Achtergrond
- Bestaand `tariff.details jsonb` bewaart de bronstructuur.
- `OcpiTariffPresentation.cs` bewaart elementen/restricties afzonderlijk, zonder toepasselijkheid te bepalen.
- `LocationEnrichmentClient.kt` reduceert restricties tot `hasRestrictions`; dit is onvoldoende om dynamische prijzen correct te tonen.
- Praktijkvoorbeeld NL/EFL: 48 uurgebonden ENERGY-elementen (twee dagen), gevolgd door een onbeperkt ENERGY-fallbackelement en andere dimensies. Niet alle elementen gelijktijdig optellen.
- Tarieven kunnen de CPO-prijs tonen; laadpas/eMSP-transactiekosten zijn niet noodzakelijk bekend.

## Scope en architectuur

Ontwerp een backend-domeinservice `OcpiTariffEvaluator` die de ruwe tarief-JSON interpreteert. Houd berekening en weergave gescheiden:

1. `TariffRepository`: alle aan een connector gekoppelde tariefobjecten, plus locatie-tijdzone en feedversheid ophalen. Ontbrekende objecten expliciet signaleren.
2. `TariffParser`: tariefelementen inclusief bronvolgorde, prijsdimensie, restricties, `step_size`, VAT en geldigheidsinterval valideren; nooit stilzwijgend onbekende regels negeren.
3. `TariffEvaluator`: evalueer voor een specifiek `asOfUtc`, locatie-tijdzone en optionele sessiecontext; produceer per dimensie toepasselijke/voorwaardelijke/onbekende componenten.
4. API vertaalt dat naar een stabiele prijsindicatie; Android presenteert, maar herimplementeert OCPI niet.

Geen berekende prijs in de database opslaan: tarieven en tijdvakken veranderen; hoogstens latere metingen/caching na expliciete prestatieanalyse.

## Semantiek

- Behoud originele elementvolgorde. Evalueer afzonderlijk per dimensie `ENERGY`, `TIME`, `PARKING_TIME`, `FLAT`; selecteer per dimensie het **eerste toepasselijke** element. Een onvoorwaardelijk element verderop is fallback voor dezelfde dimensie, niet een extra sompost.
- Binnen één restrictieobject worden alle aanwezige voorwaarden met logische AND gecombineerd. Restricties hebben betrekking op het tijdssegment / actuele sessiestatus, niet noodzakelijk de hele laadsessie.
- `start_date` inclusief; `end_date` exclusief. Lokale `start_time`/`end_time` en `day_of_week` interpreteren met de OCPI-locatietijdzone; behandel perioden over middernacht en DST expliciet. Geldigheid van top-level `start_date_time`/`end_date_time` in UTC apart beoordelen.
- Zonder betrouwbare locatie-tijdzone kan een tijdsafhankelijke prijs niet als 'nu geldig' worden gegarandeerd. Geen impliciete `Europe/Amsterdam` voor alle locaties.
- Gebruik een tri-state beoordeling voor voorwaarden: `MATCH`, `NO_MATCH`, `UNKNOWN`; onbekende sessiewaarden (`min_duration`, `min_kwh`, stroom, vermogen, reservering en dergelijke) worden niet als `false` of `true` gegokt. Bij `UNKNOWN` in een eerder element mag een later fallbackelement niet onvoorwaardelijk als definitief worden gekozen.
- `step_size` is relevant voor sessiekosten en afronding; een prijs per kWh is niet automatisch de berekende totale kostenprijs. FLAT, TIME en PARKING_TIME nooit zonder context als vaste eindprijs samenvoegen.
- Vat ontbreekt: `vatStatus=UNKNOWN`, bronbedrag ongewijzigd presenteren en geen ongegronde 'incl.' of 'excl.' claim. Rekenkundige btw-conversie alleen indien bronsemantiek en benodigde waarden bevestigd zijn.
- Wanneer meerdere tarief-ID's aan een connector zijn gekoppeld, zonder bruikbare selectieregel geen willekeurig tarief als dé prijs kiezen. Distincte opties tonen of `AMBIGUOUS` retourneren.
- Bekende bronlimitaties: ontbrekende `connector_tariff` of onoplosbare ID => `UNAVAILABLE`; niet herleiden uit andere connectoren/locaties. Feedstaleness onderscheiden van ontbrekend object.

## Voorstel API-contract (additief)

Onder bestaande `/api/v1/locations/{id}` connector, of een later gespecialiseerd connector-tariefendpoint, een optioneel `tariffEvaluation` object:

```json
{
  "asOfUtc": "2026-10-27T12:00:00Z",
  "status": "CURRENT | CONDITIONAL | AMBIGUOUS | UNAVAILABLE | STALE | UNSUPPORTED",
  "currency": "EUR",
  "isDynamic": true,
  "vatStatus": "KNOWN | UNKNOWN",
  "components": [
    {
      "dimension": "ENERGY",
      "price": 0.38,
      "unit": "kWh",
      "stepSize": 1,
      "vatPercent": null,
      "tariffId": "example",
      "elementIndex": 5,
      "certainty": "APPLICABLE | CONDITIONAL"
    }
  ],
  "limitations": ["VAT_UNKNOWN", "CPO_TARIFF_NOT_EMSP_FINAL_PRICE"]
}
```

Dit is een **schemavoorbeeld**, niet een geverifieerde werkelijke locatie of een bewezen prijs van 0,38 EUR. Echte contracttypen/JSON casing afstemmen op bestaande API. Vermijd vrije tekst als enige machineleesbare toestand. Lege/afwezige dimensies impliceren nooit nul kosten. Geen prijswaarde wanneer betrouwbaarheid ontbreekt. Voeg waar nodig `validUntilUtc` toe op basis van berekenbaar volgend prijsomslagmoment; nooit speculatief.

## Android-presentatie

- `CURRENT` vast: `€ x,xx/kWh`; `CURRENT` dynamisch: `Nu € x,xx/kWh · Dynamisch`; altijd relevante voorwaarden en aanvullende dimensies laten zien.
- `CONDITIONAL` of `AMBIGUOUS`: `Tarief afhankelijk van voorwaarden`, eventueel expliciete opties; geen misleidende enkelvoudige prijs.
- `UNAVAILABLE`: `Tarief niet beschikbaar`; `STALE`: herkenbare actualiteitswaarschuwing.
- Geen claim 'all-in' of specifieke laadpasprijs.

## Testmatrix

1. Onvoorwaardelijk ENERGY-tarief en nul-/meerdimensionale elementen.
2. EFL 48 opeenvolgende uurprijzen + fallback, met binnen en buiten het actieve tijdvak gekozen eerste match; fallback nooit optellen bij uurprijs.
3. ENERGY en TIME kunnen onafhankelijk uit verschillende elementen worden gekozen.
4. Tijdzone, daggrens, weekdag en DST-overgangen (dubbel/ontbrekend lokaal uur).
5. `start_date` inclusief, `end_date` exclusief, top-level UTC-validiteit.
6. Restricties met meerdere velden (AND), `min_duration`, `min_kwh`, `max_power` zonder/ mét sessiecontext; onbekend blokkeert schijnzekerheid.
7. Stapgrootte en eenheden van ENERGY/TIME/PARKING_TIME/FLAT.
8. Geen VAT, expliciete VAT, malformed/unsupported component.
9. Meerdere connector-tariefrelaties, ontbrekende tariefobjecten, oude feed.
10. Regression: Android ontvangt reeds bestaande details en blijft werken als `tariffEvaluation` afwezig is.

## Acceptatie en vervolg

- Bestaande APIs blijven backward compatible; nieuwe evaluator is additief.
- Foutieve of niet volledig interpreteerbare restricties leiden niet tot een schijnbaar zekere prijs.
- Testfixture van echte EFL-volgorde wordt klein en geanonimiseerd vastgelegd, zonder nationale data-export in Git.
- Eerst een kleine losse implementatie-PR voor parser/evaluator + tests, daarna API en Android, in coördinatie met nog openstaande PR #61.
- Geen wijzigingen aan productiedatabase/collector voor de evaluator vereist; hybride opslagbesluit is apart vastgelegd in audit.


## Ontwerp-review 28 oktober 2026 — Data First, tolerant maar expliciet

**Status:** aangevuld ontwerp ter review; nog geen evaluator geïmplementeerd. Leid afwijkingen van NDW nooit af tot het stilzwijgend verwerpen van een geheel tarief. Bewaar de originele `details` altijd, verzamel diagnosecodes en evalueer alle onafhankelijk veilig interpreteerbare dimensies. Een gedeeltelijke evaluatie mag nooit als complete gegarandeerde laadprijs worden gepresenteerd.

### Correcties op het eerdere voorstel

- **VAT (OCPI-betekenis):** `price` is exclusief VAT; het ontbreken van `vat` betekent in de OCPI-specificatie dat VAT niet van toepassing is, niet automatisch dat het percentage onbekend is. Maak onderscheid tussen protocolsemantiek (`NOT_APPLICABLE`, `PERCENTAGE`, `INVALID`) en vertrouwen in de NDW-/CPO-bron. Omdat een nul-/ontbrekende bron-VAT consumentenverwachtingen kan vertekenen, nooit ongekwalificeerd een incl.-btw consumenten-/laadpasprijs suggereren. Bestaande UI-tekst 'btw onbekend' uit #61 is een tijdelijke compatibiliteitsweergave, geen normatieve OCPI-interpretatie.
- **step_size:** verplicht in OCPI `PriceComponent`, met per dimensie verschillende basiseenheden (ENERGY Wh, TIME en PARKING_TIME seconden; FLAT speciaal behandelen). Een ontbrekende of onbruikbare waarde is een componentdiagnose, niet reden om andere valide elementen te verliezen. De OCPI-voorbeeldpayload met `FLAT` nulprijs en `step_size:0` verdient een aparte compatibiliteitstest; behandel niet blind iedere nulwaarde als tariefbrede fatale fout.
- **Tariff.type:** interpreteer `REGULAR`, `AD_HOC_PAYMENT`, `PROFILE_CHEAP`, `PROFILE_FAST`, `PROFILE_GREEN`. Voeg optionele `requestedTariffType` aan evaluatiecontext toe. Meerdere tarieven zijn niet automatisch onderling uitwisselbaar; zonder profielkeuze geen willekeurige winnaar.
- **Bovenliggende metadata:** parseer/rapporteer `min_price`, `max_price`, top-level geldigheid, `alt_text`, `tariff_alt_url` en valuta waar aanwezig; bronvelden blijven onaangetast. Maak onderscheid tussen actieve componentindicatie en daadwerkelijke sessiekosten.
- **Restricties:** ondersteun alle OCPI 2.2.1-velden (`start_time`, `end_time`, `start_date`, `end_date`, `min_kwh`, `max_kwh`, `min_current`, `max_current`, `min_power`, `max_power`, `min_duration`, `max_duration`, `day_of_week`, `reservation`). Combineer binnen een object met AND. Onbekende velden of context geven UNKNOWN; een eerdere UNKNOWN-kandidaat blokkeert definitieve selectie van latere fallback voor dezelfde dimensie. Een bewezen NO_MATCH hoeft geen latere fallback te blokkeren.
- **Lokale tijd:** lees OCPI `time_zone` uit `charging_location.source_data` indien aanwezig, zonder Nederland als globale default. Evalueer UTC-as-of in de gelokaliseerde tijd van de locatie; controleer middernacht, ontbrekende en dubbele uren en top-level UTC-geldigheid afzonderlijk.
- **Referentiële integriteit:** huidige `ApiDatabase.GetLocation` heeft `connector_tariff LEFT JOIN tariff`, maar slaat relaties met ontbrekend `tariff`-object over. Neem ook de onopgeloste koppelingen in de repository-uitkomst op. Onderscheid `NO_REFERENCE`, `UNRESOLVED_REFERENCE`, `MALFORMED`, `CONDITIONAL`, `AMBIGUOUS` en `STALE`; houd bestaande API intact.
- **Freshness:** Locations-feedactualiteit is niet gelijk aan Tariffs-feedactualiteit; 5/60-minuten controlecycli kunnen tijdelijk cross-feed verschillen opleveren. Gebruik specifieke Tariffs-controle/download-/importprovenance zodra beschikbaar. Identificeer een ontbrekende bronreferentie niet automatisch als importerbug of als blijvend verdwenen tarief.
- **Gedeeltelijke resultaten:** houd per dimensie de evaluatiestatus en trace (`tariffId`, `elementIndex`, restrictiematches, `stepSize`, VAT en bronmoment) bij. Eén ongeldig element hoeft niet alle andere dimensies te blokkeren; een onbekende eerdere kandidaat voor dezelfde dimensie moet wel expliciet de zekerheid beperken. Geen lege dimensie als nulprijs interpreteren.

### API-contract: aanscherping vóór implementatie

Behoud `tariffs[].details` en `presentation` volledig backward-compatible. Voeg een afzonderlijk machineleesbaar evaluatieobject toe met evaluatiecontext (`asOfUtc`, optioneel `requestedTariffType`), algemene én per-dimensie status, `tariffOptions` bij meerdere profielen, `components`, `limitations`, `sourceUpdatedAt` en waar aantoonbaar mogelijk `validUntilUtc`. Een enkele status `CURRENT` mag niet gelijk worden gesteld aan gegarandeerde eindkosten; expliciete bron-/CPO-/laadpasdisclaimers blijven vereist. De precieze DTO-namen en serialisatie eerst contracttesten.

### Regressie-fixtures en praktijkvalidatie

- Echte (klein en geanonimiseerd opgeslagen) EFL: 48 uurgebonden ENERGY-elementen plus onbeperkte ENERGY-fallback en aanvullende FLAT/TIME, eerste toepasselijke per dimensie.
- EFL/NUO/UB2: ontbrekende tariff-objecten versus geldige connectorverwijzing, en tijdelijke Locations/Tariffs-versieverschillen; nooit afleiden via nabijgelegen of vergelijkbare connectoren.
- REGULAR versus AD_HOC_PAYMENT en charging profiles; meerdere tariefreferenties; ontbrekende `tariff_ids`.
- `step_size` ontbrekend, 0 bij gratis FLAT, negatief, grenswaarde; onbekende VAT en bronkwaliteit; afwijkende/ongeldige bedragen.
- Alle restricties plus AND, UNKNOWN en fallback, weekdagen, over-middernacht vensters, DST, ontbrekende time zone en top-level geldigheid.
- Multi-dimensionale tariefonderdelen zonder onterecht totaalbedrag; regressie op bestaande API-consumenten en performance.

### Geplande kleine PR's (geen Android totdat backend gereed is)

1. Deze ontwerp-/issue-sync PR; geen productiecode of migraties.
2. Pure parser + tri-state evaluator + deterministische unitfixtures, zonder I/O.
3. Repositorykoppeling, expliciete onopgeloste relaties en getypeerde locatie-timezone/provenance, met integratietests.
4. Additief veilig API-contract, contract- en performance-regressie.
5. Indien nog nodig aparte tariff-feed-observability (collector + API); daarna pas Android-coördinatie met #61.

**Kwaliteitsprincipe:** tolerant in het **innemen en bewaren** van niet-ideale brondata; strikt in **wat als zeker of compleet aan de gebruiker wordt geclaimd**. Geen productie-ingrepen, merge of schemawijzigingen zonder expliciet akkoord.

Normatieve bron: https://evroaming.org/wp-content/uploads/2024/11/OCPI-2.2.1-d2.pdf (tariffs, restricties, PriceComponent).
