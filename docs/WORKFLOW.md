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
6. **PR aanbieden:** beschrijf wat is gedaan, hoe geverifieerd, welke aannames openstaan en welke concrete review/handmatige validatie van gebruiker nodig is. Wacht op reactie van gebruiker.
7. **Na review:** herstel op dezelfde branch, update PR en status; **merge alleen na expliciete gebruikersgoedkeuring** en na actuele CI-status. Na merge status op main controleren en volgende feature kiezen.

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
- Statusoverdracht bijgewerkt.
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
