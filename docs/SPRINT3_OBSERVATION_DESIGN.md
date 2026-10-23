# Sprint 3 — betrouwbare snapshotobservaties (ontwerp v1, 23 oktober 2026)

## Aanleiding en bewijs
De landelijke audit toont dat slechts 23,7% van de locaties exact gelijke EVSE-brontimestamps heeft. Twee NDW-bestanden op korte afstand bevatten elk 203.328 EVSE's; 199.753 records waren gelijk, 904 veranderden alleen van brontimestamp en 2.670 van status én timestamp. Een `last_updated`-update is dus niet gelijk aan een periodieke bronobservatie. De bestaande 15-minutenbronmomentregel levert waarschijnlijk te weinig dekking.

## Besluit: twee klokken, twee doelen
1. **Bronmoment** (`evse.last_updated`): wijzigings-/correctie-indicatie en versheidscontrole, geen bewijs van periodieke dekking.
2. **Moment van bewezen nieuwe landelijke snapshot** (`fetched_at`): kandidaat voor meetdekking tussen twee écht verschillende succesvolle downloads. Dit tijdstip moet door de NDW-fetcher worden meegegeven of uit gecontroleerde immutable fetch-metadata komen; niet via `now()` tijdens een historische bulk-replay worden gefabriceerd.
3. Importcommit (`completed_at`): administratief, niet het meetmoment.

## Toelatingscriteria voor één interval
- Beide snapshots succesvol en volledig binnengehaald, geparsed en transactioneel gecommit; uitsluitend **Locations**-fetches, geen tariff-only imports of HTTP 304.
- `SHA256` van Locations gzip is anders en de nieuwe `fetched_at` is strikt groter dan de vorige. Zelfde hash/zelfde snapshot: geen dekking, ook niet na replay.
- De echte download/fetch-gap is 0–15 minuten; langere gaten expliciet censureren.
- Beide EVSE-identiteiten aanwezig; bij nieuwe of verdwenen EVSE's geen interval over de grens. Alleen eenduidige bronstatussen gebruiken.
- Ongewijzigde statusklasse én geen teruggelopen brontimestamp: kandidaat-dekking over de *geobserveerde snapshot-tijden*. Bij statuswisseling, bronstatuscorrectie met gelijk timestamp of bron-timestamp-regressie: **geen tijd toeschrijven aan een van beide statussen**.
- Belangrijk: twee verschillende gzipbestanden kunnen ook door een wijziging elders in Nederland ontstaan. Daarmee is een onveranderd EVSE-record niet aantoonbaar onafhankelijk door de bron geverifieerd. Rapporteer dit als **snapshot-dekking** (sampling-assumptie), niet als gegarandeerde fysieke uptime. Expliciet datasetversheid/kwaliteitsindicatoren tonen.
- Locatieaggregatie vereist volledige gezamenlijke EVSE-status op elk van beide snapshots, **niet** gelijke `last_updated` per EVSE. Als een EVSE ontbreekt of de identiteitenset verandert, geen locatieinterval; bij UNKNOWN zonder vrije EVSE onbepaald. Interval alleen bij dezelfde bekende gezamenlijke boolean met geen tussenliggende bronstatuswijziging en voldoende versheid.

## Schema en rollout
`db/migrations/006_snapshot_provenance.sql` legt een singletoncheckpoint per feed plus een EVSE-pollcheckpoint vast. Dit schema is voorbereid en **niet toegepast op een database**. De WatchRunner en BulkImporter zijn nu gekoppeld voor uitsluitend de *provenance-checkpointopslag*: bij HTTP 200 Locations download na succesvolle validatie wordt `fetched_at` doorgegeven en SHA/fetchmoment in dezelfde importtransactie opgeslagen. Bij tarief-only, 304 en handmatige `--bulk` blijft deze write achterwege. De bestaande EVSE/locatie-statistiek berekent nog op bron-`last_updated`, **niet** op ophaaltijd.

Veilige volgorde voor de volgende codefase:
1. Fetcher-integratie: **in code toegevoegd, nog niet lokaal gevalideerd**. HTTP 200 Locations koppelt downloadmoment aan succesvol gecommit hash; 304/tarief-only en handmatige bulk genereren geen provenance.
2. Importer: **alleen provenance-write toegevoegd, nog niet lokaal gevalideerd**. Gzip SHA wordt ter controle tijdens commit berekend; bestaande minLocations/parse checks blijven gelden. Historical `--bulk` fixtures schrijven geen provenance. Bij daadwerkelijke watcher-HTTP200 is migratie 006 vereist, anders rolt de hele transactie terug.
3. **Nog open:** poll-coverage en locatiejoint-status vóór checkpoint-upsert in één transactie; pas na replay-, gap-, statuswissel-, partial-set- en rollbacktests eventueel bestaande EWMA van bronklok naar snapshotklok omschakelen.
4. Controle op echte twee opeenvolgende landelijke imports; performance en hoeveel van de 80k locaties daadwerkelijk voldoende dekking halen.

## Grenzen
Geen histogrammen van exacte sessieduur afleiden uit snapshots. Geen achteraf invullen van dagen uit lange gaps, geen fake uptime, geen publieke voorspelling zonder bewijs van voldoende dekking. PR #12 draft, expliciete mergegoedkeuring vereist.
