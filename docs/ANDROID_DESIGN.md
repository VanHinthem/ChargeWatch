# Android app + widget — ontwerp- en goedkeuringsfase

Status: **geplande verplichte fase; ontwerp nog niet gemaakt of goedgekeurd** (24 oktober 2026).

## Plaats in de planning (besloten)

1. **Laatste backendfeature (voorgesteld PR #32)**: conservatieve NDW GeoJSON-feature-ID parsing en gerichte read-only ChargeWatch-detail/statistiekenlookup op de bestaande externe OCPI-sleutel; tests en statusupdate in dezelfde PR.
2. **Design/layout app én widget**: een zelfstandige, gezamenlijke ontwerpfase. **Geen Android UI-implementatie voordat de gebruiker de ontwerpen expliciet heeft beoordeeld en goedgekeurd.** Dit is een afzonderlijke fase ná de backendstap.
3. **Android MVP**: Kotlin/Jetpack Compose; kaart rechtstreeks via NDW, ChargeWatch-details pas bij openen en ChargeWatch-kaartfallback bij NDW-uitval.
4. **Widget**: Glance-weergaven op basis van goedgekeurde widgetontwerpen en lokaal bewaarde favorieten.
5. **Publieke uitrol**: HTTPS, server-side Play Integrity-verificatie, correcte proxy-IP-behandeling, per-IP en capaciteitslimieten, release-/veiligheidstests. Dit mag pas publiek worden wanneer de afgesproken releasegates slagen.

De voorgestelde nummers zijn planning, geen vooraf aangemaakte PR's of definitieve sprintomvang.

## Designfase: scope en opleveringen

- **Use-cases en informatiestructuur**: kaart, zoek-/filtermogelijkheden, locatie-details, favorieten, laadpaalstatussen, navigatie van kaart naar detail en van widget naar app.
- **Gebruikersstromen en schermarchitectuur**: wat de gebruiker direct op de kaart moet kunnen zien versus pas in details; lege, laad-, fout- en offline-/fallbacktoestanden; toestelgroottes en oriëntatie.
- **Visuele ontwerpen**: concrete high-fidelity mock-ups/prototypes van kernschermen en app-widgetcombinaties; ten minste een compacte en een grotere widgetvariant, met aandacht voor Android resize- en refreshgedrag.
- **Gedeelde ontwerpregels**: kleurgebruik, semantische beschikbaarheidsstatussen, iconen, typografie, contrast/toegankelijkheid, light/dark mode, touch targets en consistentie tussen app en widget.
- **Inhoud en datacontract**: NDW GeoJSON voor kaartstatussen, serververrijking alleen bij details, locatie-identiteit op gecontroleerde externe OCPI-key, lokale favorieten; onderscheid tussen beschikbaar, laden/bezet, gereserveerd/defect/onbekend; nooit `total - available` automatisch als 'bezet' labelen.
- **Beoordeling**: samen opties/varianten bekijken, keuzes en open punten noteren; gebruiker geeft **expliciet ontwerpakkoord** vóór UI-code. Een PR met ontwerpbestanden en statusoverdracht wordt eveneens pas na expliciet merge-akkoord gemerged.

## Ontwerpprincipes / randvoorwaarden

- De kaart moet snel en goed scanbaar blijven; **geen onnodige ChargeWatch-verrijkingsrequests per kaartmarker**.
- Widget en app delen identiteit/statussemantiek en herkenbare visuele taal; ontwerp beide in dezelfde fase, implementeer stapsgewijs.
- Favorieten, namen en volgorde blijven uitsluitend lokaal op het toestel; geen gebruikersaccounts of server-side favorietendatabase voor MVP.
- De NDW 10 req/s-limiet is gedocumenteerd; toewijzing per publiek IP is **niet bevestigd**. Ontwerpdebounce, viewport-caching en 429/fallback-verwerking; deelquota door NAT/CGNAT blijft aandachtspunt.
- Statusvoorspellingen zijn geen harde realtime garanties; toon versheid/onzekerheid waar relevant.
- Geen redesign van de serverarchitectuur tijdens de designfase zonder apart besluit.

## Definition of done — designfase

Een goedgekeurde set app- en widgetontwerpen, vastgelegde gebruikersstromen, semantische staten, basiscomponenten en prioriteit voor MVP versus later. Documentatie (dit bestand en `docs/STATUS.md`) wordt bijgewerkt in dezelfde PR als het afgeronde ontwerp. Pas dan Android UI-featurebranches starten.

## Huidige vervolgactie

Eerst backend-lookup-PR uitvoeren en volgens werkwijze laten beoordelen. Daarna **design/layoutfase starten vóór het bouwen van de Android-client**, inclusief widgetconcepten. Bevestig keuzes tijdens de designfase; dit document legt nog geen definitieve schermindeling of huisstijl vast.
