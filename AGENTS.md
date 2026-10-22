# ChargeWatch — instructies voor iedere agent

**Bron van waarheid:** repository op GitHub; niet eerdere chatgeschiedenis. Lees bij het starten in deze volgorde:
1. [docs/STATUS.md](docs/STATUS.md) — actuele stand, volgende actie en blokkades.
2. [docs/WORKFLOW.md](docs/WORKFLOW.md) — werkwijze en menselijke beslismomenten.
3. [docs/ROADMAP.md](docs/ROADMAP.md) — prioriteiten en scope.
4. [docs/PRODUCT.md](docs/PRODUCT.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) en relevante featurespecificaties.
5. Bekijk actuele GitHub-issues, branches en open pull requests voordat je iets wijzigt.

## Werkstijl
- Werk zelfstandig **binnen afgesproken kaders**. Maak redelijke technische keuzes zonder voor ieder detail toestemming te vragen.
- Pragmatisch: kleine tot middelgrote **afgeronde features**, geen microstappen, eindeloze plannen of duizenden triviale tests.
- Bescherm echte grenzen: dataverlies, schemawijzigingen, externe writes, secrets, kosten, productie en scopewijzigingen vragen expliciete afstemming.
- Maak geen aannames over actuele code, CI, issue-/PR-status: verifieer in GitHub en vermeld wat niet gecontroleerd is.
- Scheid vastgestelde eisen, aannames en voorstellen. Werk de documentatie bij als een besluit wijzigt.
- **Geen directe commits naar main.** Werk per feature op een branch; open een PR. **Nooit mergen of deployen zonder expliciete goedkeuring van de gebruiker**, ook niet bij groene CI.
- Vraag review op betekenisvolle momenten: eerst bij onduidelijke scope, en altijd bij een afgeronde PR. Niet na iedere file edit.
- Eerlijke rapportage: noem tests die daadwerkelijk zijn uitgevoerd en die niet zijn uitgevoerd.

## Technische uitgangspunten v1
- Nederland-brede actuele NDW/OCPI laadlocaties, EVSE's, connectoren en actuele tarieven.
- .NET 10 importer schrijft; PostgreSQL/PostGIS; Android gebruikt GET-only REST API met alleen-lezen DB-rol.
- Favorieten lokaal op Android (Kotlin/Compose/Glance/Room); geen accounts, notificaties of betalingen in v1.
- Compacte statistieken vanaf eerste import: per EVSE en locatie 168 weekdag/uur-EWMA, 90-daagse uptime, compacte statusduurprofielen en locatieperioden zonder vrije aansluiting.
- V1 toont historische beschikbaarheidskansen en statistieken, maar claimt geen exact vrijkommoment.
- Geen onbeperkte ruwe eventhistorie of tariefhistorie; kwaliteit van bronwaarnemingen expliciet bewaken.

## Afronding van elk werkblok
- Houd [docs/STATUS.md](docs/STATUS.md) bij: huidige branch/issue/PR, concreet bereikt, checks, volgende stap, open keuzes.
- Bij een PR: vermeld scope, belangrijkste keuzes, verificatie, beperkingen en exact wat gebruiker moet beoordelen.
- Pas het statusdocument samen met de feature aan op de branch; pas na expliciete merge is de toestand op main definitief.
- Stop met een duidelijke overdracht; nieuwe agents mogen niet afhankelijk zijn van verborgen chatcontext.

Zie ook [docs/CHAT_START.md](docs/CHAT_START.md).
