# ChargeWatch — Notificatiespecificatie CW-ALERT-001

**Status:** Functionele wens vastgelegd, maar **uitgesteld tot na versie 1**. De onderstaande servergestuurde toestandsmachine is een *eerder ontwerpvoorstel*, niet de gekozen architectuur voor v1. De keuze tussen lokaal controleren, FCM-topics en een latere afzonderlijke gebruikers-/abonnementendatabase staat nog open. Geen code geïmplementeerd en geen melding geactiveerd.

## Configuratie

- **Locatie:** Schietspoel 18, 5348 JH Oss
- **Operator:** Vattenfall InCharge
- **Locatie (NDW):** `NL-NUO-f08bed59-be4b-42d4-8b9b-423f03f82bc6`
- **MinimumAvailable:** **1**
- **TriggerMode:** overgang van `0` naar `>=1` beschikbare EVSE's
- **OneShot:** **true**
- **Bij inschakelen al vrij:** niet onmiddellijk versturen; eerst geldige toestand met nul vrij waarnemen
- **Na verzending:** logische alert automatisch afronden

## Definitie van vrij

Een EVSE telt als vrij uitsluitend wanneer de betrouwbare bronstatus expliciet `AVAILABLE` is. `CHARGING`, `BLOCKED`, `OUTOFORDER`, `INOPERATIVE`, `UNKNOWN` en ontbrekende/verouderde statussen tellen niet als vrij. Nul vrij betekent niet noodzakelijk 'allemaal bezet': defecte en geblokkeerde EVSE's kunnen eveneens meetellen als niet-vrij. We tonen dit onderscheid in de UI.

## Toestandsmachine

1. **WAITING_FOR_UNAVAILABLE:** nieuw alert; nog geen valide moment met nul vrije EVSE's gezien.
2. **ARMED:** een geldige snapshot bevestigt nul beschikbaar. Aanmaken terwijl locatie al nul beschikbaar is, leidt direct tot ARMED.
3. **TRIGGERED:** latere, nieuwere valide status bereikt minstens één beschikbaar.
4. **DELIVERY_PENDING:** één duurzame pushopdracht in outbox geplaatst.
5. **COMPLETED:** FCM heeft de opdracht geaccepteerd; regel is one-shot afgesloten.
6. **DELIVERY_FAILED:** retrybaar verzendprobleem, niet stilzwijgend als succes markeren.
7. **CANCELLED:** door gebruiker verwijderd; nog niet verzonden berichten indien mogelijk niet afleveren.

`SOURCE_UNAVAILABLE` is een aanvullende pauze-/diagnosestatus bij ontbrekende bron, niet hetzelfde als vrij of niet vrij.

## Trigger- en veiligheidsregels

- **Niet op niveau, maar op overgang:** 2/2 bij inschakelen → geen push. Daarna 0/2 → ARMED. Daarna 1/2 → één trigger. Daarna 2/2 → geen tweede trigger.
- **Dataversheid:** voorlopig maximaal **15 minuten** sinds bronmeetmoment; configureerbaar na metingen.
- **Stabilisatie:** voorlopig bevestiging in **twee opeenvolgende succesvolle imports**, tenzij de bron een expliciet betrouwbare overgang biedt. Stabilisatie wordt gemeten en afgesteld.
- **Timestamps:** brontijd en import-/waarnemingstijd apart bewaren, alleen nieuwere gebeurtenissen evalueren; niet opnieuw triggeren op historische herimports of herstart.
- **Onbekend / bronstoring:** nooit een trigger baseren op ontbrekende, verouderde of foutieve brondata.
- **Deduplicatie:** alert-ID + triggergeneratie uniek in transactionele outbox; transacties voor trigger en outbox atomair.
- **FCM:** provideracceptatie ≠ gegarandeerde zichtbare aflevering op Android. Exact één *logische* trigger is het doel; exact één zichtbaar bericht is niet absoluut te garanderen. Client-side deduplicatie en retry/backoff beperken dubbelingen.
- **Backend herstart:** alertstatus persistente opslag, niet herbewapenen door oude imports.
- **Android offline:** notificatiehistorie beschikbaar bij volgende app-sync. Reeds verzonden push is na annuleren mogelijk niet in te trekken.

## Voorbeelden

| Begin / nieuwe status | Verwacht |
|---|---|
| Activeren bij 2/2 vrij | WAITING_FOR_UNAVAILABLE, geen push |
| Daarna 0/2 vrij | ARMED |
| Daarna 1/2 vrij, bevestigd | één push + COMPLETED |
| Daarna 2/2 vrij | geen extra push |
| Activeren bij 0/2 vrij | direct ARMED, geen push |
| Eén defect + één bezet | 0 beschikbaar, niet melden |
| Defect wordt AVAILABLE na ARMED | push indien nieuw, geldig en bevestigd |
| Bron oud / UNKNOWN | geen verandering die trigger veroorzaakt |
| Dubbele import / restart | geen dubbele trigger |

## Acceptatiecriteria

- [ ] Vrij bij aanmaken leidt niet tot directe push.
- [ ] Eerste valide nul-status bewapent de regel.
- [ ] Alleen latere geldige overgang naar >=1 triggert eenmaal.
- [ ] OUTOFORDER telt nooit als AVAILABLE.
- [ ] Verouderde of onbekende data triggeren niet.
- [ ] Herhaalde imports/herstarten veroorzaken geen duplicaten.
- [ ] FCM-fout behoudt retrybare opdracht.
- [ ] Annulering stopt nog niet verstuurde opdrachten waar mogelijk.

Deze functionele wens staat op de post-v1-roadmap. Een latere technische uitwerking moet passen bij het gekozen notificatiemechanisme; in v1 zijn geen alert-engine, pushregistratie of server-side favorieten aanwezig.
