# ChargeWatch — pragmatische agentworkflow

## Principes
Een feature is **één betekenisvol gebruikers- of technisch resultaat**: bv. NDW-import valideren, initieel schema, of locatie-API. Niet één feature per methode, maar ook niet de hele app in één PR. Streef naar beheersbare diff en één coherent acceptatiemoment.

**Gebruiker bepaalt scope en geeft altijd akkoord op de PR voordat deze wordt gemerged.** Agent voert technische uitwerking binnen de scope grotendeels zelfstandig uit.

## Standaardproces
1. **Oriënteren:** lees AGENTS.md, STATUS, roadmap en relevante specs; controleer live GitHub-branches, issues, PR's en CI.
2. **Feature afbakenen:** leg doel, in-scope, buiten-scope, acceptatiecriteria en belangrijkste risico's kort vast in een issue of PR. Alleen ontbrekende beslissende informatie aan gebruiker vragen.
3. **Featurebranch:** `feature/<onderwerp>`, `fix/<onderwerp>`, `docs/<onderwerp>` vanaf actuele main; koppel zo mogelijk een issue.
4. **Uitvoeren:** implementeren met passende tests en documentatie. Geen ongerelateerde refactors. Commit in betekenisvolle blokken; geen commits direct naar main.
5. **Controleren:** run relevante builds, tests, schema-/fixturechecks. Minimum is **risicogestuurd**, niet een vaste enorme testsuite. Voor imports/stats extra tests rond foute of ontbrekende data, idempotentie en berekeningen; UI vooral werkende flow.
6. **Mergeklare overdracht:** werk `docs/STATUS.md` op de featurebranch bij vóór het definitieve reviewverzoek, met behaalde resultaten, testbewijs, open punten en volgende actie. Beschrijf het **resultaat na merge in tijdloos, feitelijk taalgebruik** (bijvoorbeeld 'Sprint 3: implementatie en tests afgerond; volgende Sprint 4'), zonder te claimen dat GitHub vóór de merge al heeft gemerged. Vermijd bovenaan de actuele STATUS formuleringen als 'vóór merge', 'PR nog open', 'merge moet nog bevestigd worden', 'NIET gemerged' of 'na merge: ...': die worden direct na de merge onjuist. Vermeld het merge-akkoord desgewenst in het historische log, niet als actuele toestand. De status mag op main niet meteen verouderd zijn.
7. **PR aanbieden:** neem die mergeklare statuswijziging op in dezelfde PR; vermeld verificatie, risico's en wat de gebruiker moet beoordelen. Wacht op expliciet akkoord.
8. **Na akkoord:** controleer actuele head, mergeability en CI; merge de goedgekeurde head. Verifieer daarna GitHub en **de eerste regels van `docs/STATUS.md` op main**: afgeronde sprint, volgende prioriteit en deploymentstatus moeten direct kloppen. Is er toch een actuele, onjuiste status, herstel die op een korte `docs/`-branch en PR volgens dezelfde goedkeuringsregels. Maak niet standaard een losse status-PR na iedere merge.

## Wanneer de gebruiker moet beslissen
- Scope/productgedrag is onduidelijk of verandert.
- Onomkeerbare migratie, productiehandeling, kosten, secrets of toegang tot externe accounts.
- PR is klaar voor validatie; bij voorkeur korte checklist met waar op te letten.
- Merge en deployment afzonderlijk expliciet laten goedkeuren.

Niet nodig: toestemming voor ieder intern bestand, iedere test, naamgeving of kleine implementatiedetail binnen de afgesproken feature.

## Definitie van klaar
- Acceptatiecriteria aantoonbaar afgedekt.
- Passende checks uitgevoerd of beperkingen helder genoemd.
- Geen bewust onbeheerde risico's; betekenisvolle technische keuzes gedocumenteerd.
- `docs/STATUS.md` staat mergeklaar in dezelfde feature-PR, zodat na merge geen standaard correctie-PR nodig is.
- PR door gebruiker beoordeeld en expliciet goedgekeurd vóór merge.

## Testbeleid
- Zo weinig als verantwoord, zo veel als nodig; geen testen puur voor aantallen.
- Een kritieke berekening/gegevensintegriteit verdient gerichte unit/integratietests.
- Een externe databron verdient contract/fixturetest en tests voor partial failure/retry.
- Elke PR noemt exacte testcommando's en resultaten; nooit fictieve groene CI rapporteren.

## Voortgang bijhouden
- **ROADMAP:** wat en wanneer in grote lijnen.
- **GitHub issue:** wat een concrete feature inhoudt en wanneer die klaar is.
- **PR:** daadwerkelijke wijziging, verificatie en review.
- **STATUS:** wat nu loopt en hoe een volgende agent het overneemt.
- **AGENTS/WORKFLOW:** blijvende regels, niet telkens herschrijven.

Eén actieve feature tegelijk is het uitgangspunt. Parallelle features alleen bewust, met onafhankelijke branches en heldere ownership.
