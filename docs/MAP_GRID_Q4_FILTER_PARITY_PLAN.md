# ChargeWatch #113 — Q4 kaartfilterpariteit: ontwerp en acceptatiematrix

**Datum:** 3 november 2026. **Status:** reviewed against existing `src/ChargeWatch.Api/MapFilter.cs` and `src/ChargeWatch.Api/ApiDatabase.cs`; **nog geen Q4-pariteitstest uitgevoerd**. Geen code-implementatie of mutaties. Q1 (Oss: 2.101 locaties, partition_ok=true), Q2 (3 verticale grenspunten) en Q3 (8 horizontale/hoekpunten) zijn met echte read-only PostgreSQL-uitvoer geslaagd.

## Belangrijk uitgangspunt

**Niet twee filterimplementaties onderhouden.** Het huidige `MapFilter.TryParse` valideert:
- `connectorType`: `type2`, `ccs2`, `chademo`, `other` (meervoudig);
- `mode`: `ac`, `dc` (meervoudig);
- `power`: `lt50`, `gte50`, `gte150`, `gte300` (exclusief één);
- `minFree`: `0`, `1`, `2`;
- `operator`: exacte, case-sensitive? vergelijking via `btrim(l.operator_name) = ANY($n::text[])`, met max 100 operators.

`LocationPredicate(startParameter)` is de **enige bron van waarheid** voor de locatie-selectie; de nieuwe overview moet die generator en `AddParameters` met dezelfde parameters in dezelfde volgorde gebruiken. `EffectiveWattsSql` bepaalt vermogen inclusief berekende waarden bij null `max_electric_power`; meerdere connectoren op dezelfde EVSE mogen `minFree` niet dubbel tellen. Operator werkt op locatieniveau, technische filters op EVSE/connector-EXISTS, `minFree` telt alleen passende beschikbare EVSE's bij actief technisch filter. Een locatie met een technisch filter wordt geselecteerd bij minstens één *operationele* EVSE die de connectorvoorwaarde vervult.

**Belangrijk:** Beschikbaarheidssegmenten van clusters betreffen alle operationele EVSE's van *geselecteerde locaties*, niet alleen de op technische eigenschappen gefilterde connectoren. Dit volgt de in #113 afgesproken UX, maar vereist expliciete tests.

## Q4-acceptatietests

| Fixture | Input | Vergelijkingsdoel |
|---|---|---|
| Q4-00 | geen filters | Id-set van kaartlocaties overeenkomend met oude API onder 999 cap; voor grote gebieden oude endpoint **niet** gebruiken als complete bron |
| Q4-01 | `connectorType=ccs2&mode=dc` | OR binnen meervoudige typewaarden, AND tussen type/mode, exact dezelfde EVSE-connector-koppeling |
| Q4-02 | `power=lt50/gte50/gte150/gte300` | Grensgevallen en berekend vermogen via `EffectiveWattsSql` |
| Q4-03 | `minFree=1`, `minFree=2` | Unieke AVAILABLE EVSE's, geen verdubbeling door connectoren |
| Q4-04 | `connectorType=ccs2&mode=dc&power=gte150&minFree=2` | Alleen beschikbare EVSE's met een passende connector tellen voor filter |
| Q4-05 | herhaalde `operator` | Trim-/array-/filtercase naar bestaande MapFilter-contract |
| Q4-06 | combinaties met meerdere `connectorType` en `mode` | Binnen veld ANY/OR, tussen velden AND |
| Q4-07 | geen matches | Lege `markers` met geldige dekkingsmetadata; geen gedeeltelijke of onbetrouwbaar groene cluster |
| Q4-08 | techniekfilter + locatie met overige rood/grijs EVSE's | Location inclusion volgens filter, ringkleur volgens *locatiebrede* betrouwbaarheid |

## Hoe bewijs leveren zonder een handmatig gekopieerde filterimplementatie

1. Genereer beide selecties met **exact `MapFilter.LocationPredicate`** voor eenzelfde `MapFilter` en dezelfde schema-/geografische voorwaarden. De querybuilder voor overview gebruikt parameterindices passend bij `$1..$5` bbox/grid en `LocationPredicate(6)`. Voor bestaande viewport is `LocationPredicate(5)` omdat daar vier bbox-parameters zijn.
2. Test in C# op parameterindices en Npgsql-arraytypen, met werkelijke PostgreSQL-testdatabase of read-only NAS-proef zonder schema-/app-deploy. Gebruik de bestaande validator, niet een SQL-kopie in documenten. Bij inspectie van de code: `AddParameters` voegt operator, connectorType en mode toe in dezelfde volgorde als `LocationPredicate`. `power` en `minFree` zijn gevalideerde literals, geen ongecontroleerde gebruikerswaarden.
3. Vergelijk individuele feature-ID-sets op een **klein gebied onder de 999-cap** met het bestaande endpoint, of vergelijk twee SQL-selectie-CTE's. Vergelijk niet alleen totalen: aantallen kunnen toevallig gelijk zijn terwijl andere locaties zijn geselecteerd.
4. Controleer aparte read-only SQL-invarianten op grote volledige rastercellen: `count(DISTINCT feature_id)=sum(locationCount)` en elke locatie valt precies in één cel na alle filters.
5. Voor gekleurde ringsegmenten valideer onafhankelijk dat de aantallen per segment sommeren tot cluster-`locationCount`. De huidige Q1-classifier is niet geschikt als bewezen productielogica totdat Q5 bronbetrouwbaarheid is uitgewerkt.

## Huidige blockers / eerlijke status

De bestaande `MAP_GRID_A1B_FILTER_GRID_REVIEW.md` is een **niet uitvoerbaar C#-SQL-template** met de letterlijke placeholder `{MapFilter.LocationPredicate(startParameter: 6)}`. Direct uitvoeren in `psql` toont **niet** filterpariteit; daarvoor moet de echte C# generator worden aangeroepen of minimaal een expliciet als tijdelijk gemarkeerde SQL-referentie worden ontwikkeld en onafhankelijk vergeleken. Aangezien de gebruiker alleen SQL-read-only-benchmarks toestond en nog geen productcode-implementatie, worden **geen ongemerkte wijzigingen aan API/Android** gemaakt. Zodra toestemming voor testharnesstestcode/implementatie duidelijk is kan dit geheel geautomatiseerd worden; voorlopig is dit de functionele specificatie en acceptatiematrix.

Andere risico's: de oude viewport kent een 999-limiet en levert extra connectorobjecten; de nieuwe overview bevat doelbewust clusters i.p.v. locatiefeatures, dus vergelijk *pre-cluster* sets, niet ruwe responsevormen. Test exact lokalisatie van `operator` null/blank, status `PLANNED/REMOVED`, dubbele connectoren, stale feed en zero-availability.

**Geen productie writes, migrations, merges, releases of deployments.**
