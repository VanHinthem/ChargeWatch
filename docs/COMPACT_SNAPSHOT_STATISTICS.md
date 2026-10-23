# ChargeWatch — compacte snapshotstatistieken (ontwerp / acceptatiecriteria)

**Status:** ontwerpvoorstel; geen implementatie, NAS-wijziging of productie-migratie. Apart van NAS-back-up PR #16.

## Doel en betekenis

ChargeWatch verzamelt iedere circa vijf minuten de door NDW **gepubliceerde** laadpuntstatus en werkt compacte, online statistieken bij. `observed_at` is het feitelijke tijdstip waarop de succesvolle NDW-locatiesnapshot is opgehaald; `source_last_updated` is diagnostische metadata, **geen verplichte statuswijziging of voorwaarde dat een status meetelt**. Een laadpunt kan dagenlang terecht AVAILABLE blijven.

Het doel is een bruikbare schatting van *door NDW gepubliceerde beschikbaarheid* per Nederlands weekdag-/uurslot, **niet** een exact fysiek omslagmoment of bewezen continu operationele periode. Dezelfde gepubliceerde status op twee afzonderlijke echte fetches is twee snapshotwaarnemingen; een herimport uit cache/herstel/test is **geen** nieuwe zelfstandige fetch.

## Apart bewaren wat later anders kan worden geïnterpreteerd

Bewaar per EVSE en per locatie een compacte statistische toestand voor de 168 combinaties van Nederlandse weekdag en uur (DST expliciet). Bewaar de categorieën afzonderlijk; voeg UNKNOWN nooit vooraf bij bezet of defect. Minimale EVSE-categorieën:

- AVAILABLE
- CHARGING
- BLOCKED
- RESERVED
- INOPERATIVE
- OUTOFORDER
- UNKNOWN / niet-classificeerbaar
- PLANNED
- REMOVED
- MISSING (niet aanwezig in volledige snapshot; **kwaliteit/lifecycle, niet hetzelfde als NDW-status REMOVED**)

Bewaar zowel **cumulatieve tellers/gewichten** als, indien gewenst, **categorie-specifieke EWMA** per slot, met aantallen en kwaliteitsdekking. Interpretaties zijn afgeleid bij uitlezen, niet in de teller versmolten. Een EWMA op zichzelf kan niet exact worden herberekend onder een andere definitie; categorische tellers en gewichten moeten behouden blijven. De uiteindelijke keuze tussen vaste gewichten per valide snapshot en getemporiseerde gewichten moet in de benchmark worden onderbouwd. Tellers zijn niet automatisch fysiek bewezen duur.

Voor locaties: bepaal de gezamenlijke snapshotklasse uit alle actuele EVSE's die werkelijk bij die locatie horen. Bewaar afzonderlijk: aantoonbaar >=1 vrij; geen vrij met alle actieve EVSE's bekend niet-vrij; onzeker door UNKNOWN/ontbrekend; uitsluitend defect; geen actieve capaciteit (PLANNED/REMOVED); incomplete locatie-snapshot. **Niet** locatiekans uit EVSE-kansen vermenigvuldigen.

## Expliciete regels per waarneming

| Situatie | EVSE-statistiek | Locatie-statistiek | Kwaliteit |
|---|---|---|---|
| Succesvolle, gevalideerde *nieuwe* volledige locations-fetch; EVSE aanwezig met AVAILABLE | vrije categorie +1 | >=1 vrij als dit in dezelfde complete locatie-snapshot geldt | geldige NDW-publicatie |
| CHARGING/BLOCKED/RESERVED | eigen niet-vrije categorie +1 | geen vrij als alle relevante statusinformatie volledig bekend is | geldige publicatie |
| INOPERATIVE/OUTOFORDER | eigen defectcategorie +1 | defect/geen bruikbare capaciteit afzonderlijk | geldige publicatie |
| UNKNOWN | UNKNOWN +1 | onzeker, tenzij een andere EVSE ondubbelzinnig AVAILABLE is | statusdekking onbekend |
| PLANNED/REMOVED | eigen lifecycle-categorie +1, niet in noemer actieve-capaciteit | geen actieve capaciteit onderscheiden | wel geteld, niet als bezet |
| Status/brontimestamp onveranderd bij **nieuwe onafhankelijke feed-fetch** | categorie opnieuw tellen | overeenkomstig | geen verplichte bron-timestampwijziging |
| HTTP 304 of identieke inhoud bij een nieuwe geslaagde netwerkcontrole | telt als nieuwe waarneming van de NDW-gepubliceerde status; apart label `unchanged_feed` | idem | geen bewijs van een nieuwe fysieke statusmelding |
| Download, validatie of import mislukt / rollback | geen update | geen update | fout afzonderlijk registreren |
| Onvolledige locations-dataset of ontbrekende EVSE | geen status verzinnen; markeer missing/coverage | onzeker als volledige locatie niet betrouwbaar vaststaat | geen massadeactivatie |
| Lange fetch-gap | geen interval overbruggen; eerstvolgende geldige snapshot als nieuwe puntwaarneming | idem | gap-teller vastleggen |
| Statuswissel tussen twee fetches | nieuwe toestand geldt op het **observatiemoment** | idem | fysiek omslagmoment is onbekend |
| Tarief-only verandering | geen beschikbaarheidsobservatie | geen | categorie onafhankelijk van tarieven |
| Herstel, replay, handmatige retry van dezelfde snapshot | idempotent, geen dubbel gewicht | idem | expliciete snapshot-identiteit |

**Nuance:** twee succesvolle HTTP-200 responses met identieke feed-inhoud bewijzen niet automatisch twee onafhankelijke statuspublicaties. Een succesvolle afzonderlijke netwerkcontrole (ook HTTP 304) mag meetellen voor de gepubliceerde-statusstatistiek; lokale cache-replays, retries van dezelfde poll, tarief-only imports en rollbacks niet. Dit is geen bewijs van fysieke statusversheid.

## Lifecycleregels voor verwijdering uit NDW

Een ontbrekende EVSE of locatie is niet per definitie `REMOVED`. Bewaar `last_seen_at`, `first_missing_at`, aantal *opeenvolgende gevalideerde volledige snapshots* waarin het object ontbreekt en een zichtbaarheidstoestand. Pas een object na meerdere onafhankelijke, complete snapshots en afgesproken verificatietermijn als inactief aan; nooit na een gedeeltelijke of onbetrouwbare import. Bij terugkeer heractiveren en behoud categorische aggregaten. Actuele catalogus en historische statistiekstatus logisch scheiden; geen fysieke catalogus-delete als standaard. Inactieve objecten tellen niet mee in actuele beschikbaarheid.


## Ongewijzigde feed en nachtelijke stilstand — te valideren ontwerpkeuze

Een landelijke NDW-feed kan ook zonder nieuwe statuswisselingen geldig bereikbaar zijn, bijvoorbeeld in rustige perioden. **Geen hashwijziging is geen bewijs van feedstoring.** Daarom geldt voorlopig:

- Nieuwe, succesvolle netwerk-fetch of HTTP 304 met ongewijzigde NDW-locatiesinhoud: **wel meetellen** als nieuwe observatie van de laatst gepubliceerde NDW-status, met afzonderlijke kwaliteitscategorie `unchanged_feed`; niet behandelen als een nieuwe fysieke statusmelding. Houd afzonderlijk bij hoeveel observaties uit ongewijzigde feeds komen. Geen onbewezen werkelijke uptime of exacte duur claimen.
- Nieuwe volledige gewijzigde locatiesfeed: geldige observatie, ook voor EVSE's met ongewijzigde status en bron-timestamp.
- Cache-replay, retry van eerder gedownloade bytes zonder nieuwe netwerk-fetch, rollback en tarief-only update: geen nieuwe locatiesobservatie.
- NDW onbereikbaar: meetgat, geen statistische observatie.
- Een ongewijzigde feed mag **niet automatisch na 15 minuten als storing worden aangemerkt**. Eventuele waarschuwing vereist aanvullend onafhankelijk versheidssignaal of gekalibreerde afwijking van normaal gedrag; `last_modified`, ETag en bronmetadata moeten eerst onderzocht worden.

Scheid compacte tellers voor `successful_fetch`, `changed_publication`, `unchanged_feed`, `cache_replay`, `failed_fetch` en `unknown_source_freshness`. Daarmee blijven interpretaties later aanpasbaar zonder ruwe per-EVSE snapshots te bewaren. Test in de feature zowel langdurige onveranderde nachtelijke feeds als een daadwerkelijk vastgelopen publicatieketen, en toets bias, ontbrekende dekking en schaalbaarheid vóór het definitieve statistiekcontract. Leg bij iedere output de herkomst en mate van bewijs vast; een netwerk-fetch bevestigt gepubliceerde inhoud, niet de fysieke status van het laadpunt.

## Opslag- en schaalbaarheidskaders

Geen ruwe EVSE-per-poll historie. Alleen laatste waarneming/checkpoint, per-slot compacte categorietellers / gewichten (en optionele EWMA), compacte kwaliteits-/lifecycle-tellers, schema-/algoritmeversie. Gebruik in de uitvoering een **delta- of batchbenadering** zonder 200k x 168 brede rijupdates iedere vijf minuten. Schat storage, WAL, schrijfduur en indeximpact met werkelijke ~203k EVSE's en ~80k locaties op geïsoleerde testdatabase. Meet tevens impact van de bestaande 5-minuten collector. Back-ups en onderhoud blijven beheersbaar.

## Acceptatiecriteria

1. Identieke status met onveranderde `source_last_updated` telt als nieuwe geldige snapshot **als en alleen als** deze als nieuwe onafhankelijke publicatie is gekwalificeerd.
2. Exact dezelfde import opnieuw uitvoeren is idempotent, ook na crash/rollback; snapshot-identiteit en statistieken worden transactioneel gepubliceerd.
3. Elke NDW-statuscategorie, UNKNOWN, PLANNED en REMOVED houdt eigen tellers; verschillende uitleesdefinities geven verschillende percentages **zonder een herimport**.
4. Bij 1-vrije-plus-1-UNKNOWN locatie is 'minstens één vrij' bekend; bij nul vrij plus UNKNOWN blijft onzeker; ontbrekende EVSE maakt incomplete locatie expliciet.
5. Geen fictieve dekkingsduur over downloadgaps, gedeeltelijke feeds of onzekere statuswisselintervallen. Aantal waarnemingen is geen bewijs van uptime.
6. Weekdag/uur volgens Europe/Amsterdam, met tests voor zomertijd, wintertijd en dubbele lokale uren.
7. Verdwenen en teruggekeerde EVSE/locatie met meerdere onafhankelijke complete snapshots getest; geen onbedoelde verwijderingen of statistiekvervuiling.
8. Eerste snapshot heeft geen fictieve voorhistorie; een gemiste poll levert geen geconstrueerd datapunt.
9. Uitkomst bij onvoldoende representatieve observaties blijft **'Onvoldoende historische gegevens'** conform `docs/SPRINT3_PUBLICATION_POLICY.md`; geen gebruikersgerichte kansen zonder latere kwaliteitsgate.
10. Isolatie: schema en runtime eerst op aparte PostgreSQL-database; relevante regressies + nationale benchmark met opslag/WAL/performance; daarna review en expliciet merge-akkoord. NAS-upgrade afzonderlijk accorderen.
11. Uitvoering moet rekening houden met al geïnstalleerde tabellen 003–007 en bestaande aggregaten; geen historische meetpunten fabriceren of oude en nieuwe algoritmen stilzwijgend mengen.
12. Alle beslisregels voor inclusie, ontbreken, duplicaten en uitsluiting krijgen gerichte automatische tests, maar testscope blijft pragmatisch.

## Werkvolgorde

1. Inventariseer huidige import-, checkpoints-, snapshot- en statistieklogica; leg exacte verschillen vast.
2. Ontwerp minimaal aanvullend schema en transactioneel snapshot-contract; bepaal onafhankelijkheidsregel.
3. Implementeer afzonderlijke categoriegewichten, locatie-aggregatie en idempotentie op feature branch.
4. Test echte feedvoorbeelden, hiaten, duplicaten, terugkeer, DST en nationale benchmark in geïsoleerde database.
5. Werk `docs/STATUS.md` bij **in dezelfde feature-PR**, met correcte toestand na merge. Geen merge zonder gebruikersakkoord; geen NAS-deployment zonder apart akkoord.

## Vastgelegd gebruikersbesluit — 23 oktober 2026

**Alle succesvolle nieuwe locaties-pollmomenten tellen voor de NDW-gepubliceerde-statusstatistiek**, ook wanneer de hele landelijke feed identiek blijft. Pollmoment/idempotentiesleutel voorkomt dubbeltelling van retries; lokaal herverwerken zonder nieuwe netwerkcontrole telt niet. Een HTTP 304 bevestigt dat de server geen andere versie aanbiedt en telt als afzonderlijk pollmoment; het is geen bewijs van een nieuwe bronpublicatie. Bij storing geen imputatie over gemiste polls. Categorie en kwaliteit (`changed_feed`/`unchanged_feed`, bronouderdom) apart compact accumuleren om interpretaties later te kunnen wijzigen. Geen claim van fysiek bewezen uptime.
