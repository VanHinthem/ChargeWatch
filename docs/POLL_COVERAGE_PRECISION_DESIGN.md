# Issue #72 — ontwerp exactere poll-uurdekking (concept)

Status: **ontwerp, niet geïmplementeerd**. PR #83 bevat reproducties en tests; productie blijft ongewijzigd.

## Afbakening

De test met de echte `SnapshotPollCoverage.Capture` toont een fout aan: vijf opeenvolgende intervallen van 600,6 s en één van 597 s beslaan exact 3600 s, maar worden door `extract(epoch FROM ...)::integer` opgeteld tot 3602 s. De bestaande 3600-guard weigert de laatste 597 s; 3005 s blijft bewaard.

De waarschuwingen in productie zijn **niet** volledig aan deze oorzaak toegeschreven. De gewone checkpointketen voorkomt in de geteste scenario's replay en overlapping; de losse kunstmatige overlaptest bewijst niet dat zulke overlappingen in productie optreden.

## Voorgestelde minimale opslag

Breid `evse_poll_hour_coverage` uit met nullable `covered_microseconds bigint` en `available_microseconds bigint`. Bewaar bestaande `covered_seconds` en `available_seconds` als historische/compatibiliteitswaarden totdat de lezers zijn omgezet. Gebruik CHECKs voor bereik 0–3.600.000.000 microseconden en `available <= covered`; gebruik BIGINT om overflow van tussentijdse berekeningen te vermijden. Tel voor nieuwe uurrecords de exacte duur in microseconden op; rond pas bij presentatie af. Bij gesplitste intervallen moet elk deel exact op de UTC-uurgrens eindigen/beginnen.

**Overgang oude records:** bestaande seconden bevatten onherstelbare afronding. Niet stilzwijgend `seconds * 1000000` als exacte provenance presenteren, en niet bestaande uurrecords opnieuw aanvullen vanaf onbekende intervallen. Een gecontroleerde overgang op een UTC-uurgrens maakt oude uren legacy en start nieuwe uren met nieuwe precisie. Bepaal expliciet of de app/API legacy en nieuwe waarden van elkaar kan onderscheiden. Voor gemengde uren: óf blokkeren/markeren als legacy, óf apart en aantoonbaar migratiebeleid; geen impliciete vermenging.

## Schrijf- en replayregels

- De globale `ndw_snapshot_checkpoint` en per-EVSE `evse_poll_checkpoint` blijven binnen **dezelfde transactie** als dekking en cataloguswijziging. De geobserveerde fetchmomenten moeten strikt oplopen en dezelfde SHA / oudere replay mogen geen nieuwe dekking veroorzaken.
- Behoud bronversheidsgrens, maximaal 15 minuten tussen opeenvolgende fetchmomenten, en alleen ongewijzigde geldige status voor continue dekking.
- Nieuwe microseconden-UPSERT mag alleen toevoegen wanneer som <=3.600.000.000 én available <= covered; log onderscheid tussen precisie-overschrijding, duplicate/replay, werkelijk onbewijsbare overlap en legacy-uren. Weigeren of behouden zonder stille clipping.
- **Microseconden lossen overlap niet op.** Als overlap in een daadwerkelijk mogelijk collectorpad wordt aangetoond, moet dat apart worden opgelost; cumulatieve tellers kennen geen interval-union.
- Volledige uren zijn geen bewijs van 100% betrouwbare NDW-feed; dekking vereist ook actuele bronstatus en kwaliteitslabels.

## Beoordeling van lezers

De huidige broncode van `src/ChargeWatch.Api/ExperimentalAvailability.cs` gebruikt compacte EWMA-statistieken en leest de poll-coverage-tabel niet. De poll-hour-coverage-migratie noemt dit zelf een onafhankelijk experimenteel spoor. Vóór implementatie moeten *alle* overige SQL-/API-/rapportagelezers op de branch worden geïnventariseerd; de aanwezigheid van legacy-kolommen blijft voorlopig behouden.

## Verificatie en rollout

1. Bevestig met echte Capture-integratietests: aaneengesloten subsecond-intervallen tellen exact 3600 s; geen afgewezen geldige laatste increment; eerste opname, replay, SHA-gelijkheid, statuswissel, gap >15min en hour-boundary-splits.
2. Voeg SQL-migratie-/constrainttests en backward compatibility check toe; test de migratie van een bestaande legacy-rij.
3. Meet opslag en importlatentie op realistische fixtures; geen volledige landelijke intervallog per poll zolang daar geen hard bewijs voor nodig is.
4. Maak expliciete herstel- en monitoringregels; **geen automatische backfill** van 24–28 oktober alsof exacte intervallen bekend zijn.
5. Na groen CI, review en expliciet akkoord pas deployen met verplichte pre-migration backup en afzonderlijke validatie in productie.

## Open vragen

- Exacte bijdrage van per-interval afronding aan de 123.421 onderdrukte increments (niet vast te stellen uit geaggregeerde productiehistorie alleen).
- Welke bestaande dashboards, diagnosequeries of andere lezers nog aan `covered_seconds` gekoppeld zijn.
- Hoe legacy-uren visueel/API-technisch worden gemarkeerd en hoe de overgang wordt gepland.
