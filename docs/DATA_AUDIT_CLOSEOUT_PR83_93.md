# Afsluitende datareview — PR #83 t/m #93

Reviewdatum: 29 oktober 2026. Implementatiebasis: main `c7dfe04436feb9e8481f8485565c212605eed047` na squash-merge van PR #93. Scope: afsluitende review van de data-first reeks, gerichte restbevindingen, CI-bewijs en bekende productiegrenzen. Dit document is geen deploymentbewijs voor #93.

## Oordeel

De data-audit kan op **implementatieniveau** worden afgesloten. De twee resterende functionele gaten uit de review na PR #90 zijn niet alleen gedocumenteerd maar in afzonderlijke, begrensde fixes opgelost:

1. PR #92 voorkomt dat een uit een actuele NDW Tariffs-snapshot verdwenen tarief door bewaarde historie toch als actuele toepasbare prijs verschijnt.
2. PR #93 voorkomt dat één onvolledige `connectors`-collectie connectoren van die EVSE onterecht presence-evidence geeft of de gehele landelijke import blokkeert.

Dit past bij het vaste beleid: NDW/OCPI is bron van waarheid, brondata en relaties worden maximaal behouden, onzekerheid wordt expliciet gemaakt en lokale bronfouten worden op het kleinst veilige niveau geïsoleerd.

## F1 — tariefaanwezigheid: opgelost in #92

PR [#92](https://github.com/VanHinthem/ChargeWatch/pull/92) introduceert gescheiden snapshot-presence voor tarieven. Historische payloads en connectorreferenties blijven behouden; actuele toepasbaarheid vereist aanwezigheid in een geaccepteerde volledige Tariffs HTTP 200-snapshot. HTTP 304, cache-replay en handmatige bulkimport leiden geen afwezigheid af. Verdwijning resulteert in `SOURCE_MISSING` zonder prijscomponenten; terugkeer reactiveert dezelfde tariefidentiteit.

Veiligheidsgrenzen weigeren lege of verdacht sterk gekrompen Tariffs-snapshots. De geïsoleerde WatchRunner/API-contracttest dekt aanwezig → verdwenen → 304 → terugkeer plus partiële en lege feeds.

**Productiebewijs:** #92 is door de gebruiker op de NAS uitgerold. Migratie `025_tariff_snapshot_presence` is toegepast na back-up `chargewatch-20261029T085441Z.dump`; collector en publieke API zijn daarna gezond bevestigd. De eerste presence-baseline wacht bewust op een echte gewijzigde HTTP 200 en wordt niet uit een 304 afgeleid. Een geïsoleerde restore van deze specifieke back-up is niet aangetoond.

## F2 — connectorcollectie-completeness: opgelost in #93

PR [#93](https://github.com/VanHinthem/ChargeWatch/pull/93) maakt completeness per EVSE expliciet. Een ontbrekende, `null`, verkeerd getypeerde of lege `connectors`-collectie wordt alleen voor die EVSE uitgesloten van connector-presence-evidence. De laatst geldige connectoren en bestaande missing-tellers blijven staan; locatie-/EVSE-status en alle geldige overige records uit dezelfde landelijke snapshot worden normaal verwerkt.

De echte HTTP-contracttest voert vier afzonderlijke onvolledige Locations HTTP 200-responses uit met telkens een verse cache. Hij bewijst dat de foutieve EVSE geïsoleerd blijft, dat de snapshot commit en dat een andere EVSE normale missing-evidence opbouwt. De bestaande migratie-022-smoketest blijft individuele connectorverdwijning, drie-snapshot-/tienminutenhysterese, historiebehoud en terugkeer dekken.

**CI-bewijs laatste #93-head:** zeven workflows success: Connector collection completeness, Compact Statistics Watch Integration, Compact Statistics Database, Compact National Import Benchmark, Tariff snapshot presence, Read-only API en NAS Images.

**Productiegrens:** #93 is gemerged naar main als `c7dfe04436feb9e8481f8485565c212605eed047`, maar een NAS-uitrol van deze release is nog niet door de gebruiker bevestigd. Migratie `026_connector_collection_completeness` en een normale volledige Locations-import moeten bij een afzonderlijk goedgekeurde release nog in productie worden gecontroleerd. Dit is releasevalidatie, geen resterend implementatieontwerp.

## Reeks #83–#93 in context

- #83 onderzocht en corrigeerde precisie in de inmiddels retired intervalcoverage-keten.
- #84 en #85 voegden behoudende EVSE-/connector-presence toe.
- #86–#88 maakten operationele capaciteit, connectorvermogensprovenance en source freshness explicieter.
- #89 en #90 verwijderden de ongebruikte legacy interval-/duurstatistiekketens en bijbehorende opslag.
- #92 en #93 sloten de twee functionele gaten die de afsluitende review van die reeks nog vond.

De oude issues #72 en #11 verwijzen grotendeels naar de retired legacy-statistiekpaden. Zij mogen niet worden gelezen als opdracht om de verwijderde ketens opnieuw op te bouwen. Administratieve herijking/sluiting is passend; de actuele compacte snapshotstatistieken en hun API-contract blijven de runtimebasis.

## Bekende grenzen die geen blokkade voor data-auditcloseout zijn

- Uurfinalisatie op de NAS is op verzoek bewust uitgesteld; dit is geen impliciete productieacceptatie.
- Exacte opslag-/WAL-winst van de legacy-cleanup is niet opnieuw gemeten op de NAS.
- De tarief-presence-baseline ontstaat pas bij een gewijzigde Tariffs HTTP 200.
- Een groene PR-image bewijst geen NAS-deployment.
- NDW-bronfouten of ontbrekende bronvelden kunnen blijven voorkomen; het doel is betrouwbaar behoud, isolatie en expliciete onzekerheid, niet het repareren van de bron.

## Volgende productstap

Na inhoudelijke validatie en merge van deze documentaire closeout kan de productfocus terug naar Android. Open PR #61 moet eerst worden herbeoordeeld tegen het definitieve tariefcontract, source freshness en de huidige connector-/operationele-capaciteitssemantiek. Nieuwe UI-wijzigingen horen in een afzonderlijk beheersbaar Android-werkblok met Samsung-validatie.

Een NAS-uitrol van #93 blijft daarvan losgekoppeld en vereist expliciet deploymentakkoord.
