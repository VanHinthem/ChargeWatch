# ChargeWatch #113 — terug naar server-side kaartaggregatie: performancewerkpakket

**Besluit 3 november 2026:** Q1–Q4-validatie als basis behouden. De aanvullende Q5a–Q5d-status-/bronkwaliteitsexploratie wordt **geparkeerd** voor dit issue: de bestaande NDW-import-, lifecycle-, beschikbaarheids- en kwaliteitsregels worden hergebruikt; geen tweede classifier of herontwerp van deze domeinlogica als onderdeel van #113. Q5a–Q5c blijven historische read-only bevindingen, Q5d blijft expliciet een niet-besloten concept. Dit document is het leidende vervolg voor #113.

## Doel en huidige voortgang

De bestaande kaartselectie loopt tegen een limiet van 999 laadlocaties. #113 richt zich op **landelijk dekkende server-side Web Mercator-rastercellen**, met een gelijke filtersemantiek als de bestaande `MapFilter`, en daarna uitsluitend doelgerichte performance-optimalisatie voordat API en Android worden aangepast.

- **Q1–Q3 PASS:** integriteit, unieke celtoewijzing en grens-/hoekgevallen van Web Mercator-grid.
- **Q4-00..07 PASS voor de uitgevoerde datasets:** precluster locatie-ID-setpariteit van viewport en raster (geen filters, CCS2/DC, vier vermogensbanden, minFree=1/2, gecombineerde filters, operatoren, multivalue filters, geen matches). De productie-`MapFilter.cs` wordt vanuit de C# harness direct hergebruikt.
- **Q4-08 basiscontrole PASS:** geselecteerde locaties en locatiebrede tellingen zijn intern consistent; beide werkelijk gevonden locaties hadden echter geen aanvullende niet-passende EVSE's. De aanvullende synthetische Q4c-08b-case en Q4c-04c connector-deduplicatie zijn **nog niet als uitgevoerd bevestigd**. Ze zijn regressietest/backlog bij de implementatie, geen reden om het bestaande datamodel opnieuw te onderzoeken.
- **Q5a–Q5c:** uitgevoerde diagnostische status-/brontimestamptests, niet nodig als nieuwe aparte ontwerpfase van #113. **Q5d is niet goedgekeurd en geen implementatieopdracht.**

## Al uitgevoerde A1–A4 benchmarks — niet opnieuw doen alsof ze nieuw zijn

Bestaande read-only NAS-metingen (eenmalige runs; JIT uit; niet gecontroleerd op cache-/collectorbelasting):

| Variant | Oss (~2100 locaties), ms | Grote regio zoom 8 (~70k locaties), ms |
|---|---:|---:|
| A1 directe join + aggregatie | 78,890 | 844,151 |
| A2 vooraf aggregatie over actieve EVSE's | 369,545 | 743,756 |
| A3 geselecteerde locaties + LATERAL per locatie | 104,811 | 1100,014 |
| A4 geselecteerde locaties + join/group | 229,671 | 763,406 |

**Indicatie, geen definitieve ranking:** A1 was snelst voor kleine regio; A2/A4 waren in de enkele grote run sneller dan A1, maar niet overtuigend anders onder onbekende cache- en systeembelasting. A3 was in beide samples niet beter dan A1 (klein) of A2/A4 (groot). Niet opnieuw allerlei nieuwe SQL-varianten verzinnen zonder een concrete bottleneck.

## Volgende stap P1 — gecontroleerde prestatierun

1. Meet opnieuw **uitsluitend A1 en A2** bij exact gelijkblijvende, complete celdekking en een gelijkwaardige NDW-snapshot, eerst klein (Oss) en daarna de reeds eerder geteste grote regio. Houd SQL-structuur, filters en outputclassificatie identiek aan de bestaande A1/A2-benchmarks: dit zijn queryplan-/capaciteitstests, geen productiebeschikbaarheidsbeslissing.
2. Herhaal elke variant ten minste drie keer in afwisselende volgorde (`A1,A2,A2,A1,A1,A2`) en noteer uitvoeringstijd, planningstijd, aantal cellen/locaties, `shared hit/read`, `temp read/written`, hash-/sort-disk-spills, join-strategie, index scans en tijdstippen. Gebruik `EXPLAIN (ANALYZE, BUFFERS)` op `SELECT` onder `chargewatch_read`, `REPEATABLE READ READ ONLY`, `statement_timeout=5s`, `jit=off`. De oorspronkelijke scripts staan in [A2](MAP_GRID_A2_PREAGG_BENCHMARK.md) en [A1](MAP_GRID_A1_SQL_PROBE.md); oude A3/A4 als alternatieven/documentatie behouden.
3. **Eerst alleen de keuze A1 versus A2** voor kleine/grote aantallen toetsen; geen harde grenswaarde uit de losse benchmarks afleiden. Controleer of de bestaande PostgreSQL-planselectie een adaptieve keuze überhaupt nodig maakt.
4. Pas bij aantoonbare bottleneck één gerichte optimalisatie onderzoeken (index/planner/aggregate) en onder exact dezelfde voorwaarden opnieuw meten. Geen index-/schemawijziging in productie zonder apart akkoord; `EXPLAIN ANALYZE` blijft read-only.
5. Daarna op landelijke zoomniveaus een schaaltest en responsebudget vaststellen. **Geen onbewezen performanceclaim of 'landelijk snel' uit de ~70k grote-regio-proef afleiden.**

## Daarna: implementatie-afbakening

Zodra een queryvorm onder gecontroleerde tests stabiel is: implementatieplan voor API overview endpoint, paginering/dekkingsmetadata, cellen / clusters, consistente locatieweging, bestaande `MapFilter` en bestaande classificatie als gedeelde logica; later Android-zoomgedrag. Definieer acceptatietests voor endpoint, filtering, volledigheid (geen 999-cap), statusring en regressies. **Geen code-/DB-productieimplementatie, merge, of NAS-deploy zonder expliciete gebruikersgoedkeuring.**

## Niet opnieuw onderzoeken in #113

Geen nieuwe definitie van de NDW-bron van waarheid, AVAILABLE/UNKNOWN of PLANNED/REMOVED, geen eigen feed-versheidsregime of nieuwe tweede domeinclassifier, geen opnieuw bouwen van eerder gevalideerde statistieken. Alleen waar het overview-endpoint een *concrete niet-afgedekte regressie* vertoont, een kleine gerichte test toevoegen.

**PR #114 blijft draft**. Bestaande Q5-documenten blijven beschikbaar als onderzoeksarchief maar zijn niet leidend voor de voortgang van #113.
