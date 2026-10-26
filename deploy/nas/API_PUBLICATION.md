# Productie-API — publieke HTTPS-entrypoint (voorbereiding)

Definitieve naam: **https://chargewatch-api.vanhinthem.nl**. Eén API, één bestaande
productiedatabase; geen ACC/DEV en geen tweede gebruikersdatabase.

## Vastgestelde huidige grenzen

- `deploy/nas/compose.yaml` bindt uitsluitend `127.0.0.1:5088:8080` voor de API.
- PostgreSQL is niet gepubliceerd via hostpoorten en de API gebruikt `chargewatch_read`.
- De API is GET-only, maar huidige IP-limiter is 120/min; *read-only is niet
  automatisch DDoS-, scraper- of volumebeveiliging*.
- `UseForwardedHeaders` is actief voor X-Forwarded-For en X-Forwarded-Proto.
  ASP.NET Core moet forwarded headers uitsluitend van vertrouwde lokale proxy
  accepteren; verifieer expliciet het werkelijke peeradres en het aantal hops.
- De productie-Android-app mag geen service-account, PostgreSQL-credentials,
  statische private key of gedeelde bearer-secret bevatten.
- Play Integrity serververificatie is **nog niet geïmplementeerd**. Dit is een
  bewuste resterende gate vóór brede publieke appdistributie.

## Topologie (geen extra database of API-container)

```text
Samsung / Android
    |
 HTTPS 443, geldig certificaat, hostname chargewatch-api.vanhinthem.nl
    |
 NAS reverse proxy (vertrouwde client-IP informatie, rate limiting)
    |
 http://127.0.0.1:5088 -> Compose api:8080
    |
 chargewatch_read -> interne PostgreSQL 18
```

Configureer in de bestaande NAS reverse proxy een **dedicated HTTPS-host**:
- inkomend: `chargewatch-api.vanhinthem.nl` op 443, geldig TLS-certificaat;
- upstream: `http://127.0.0.1:5088` op dezelfde host;
- behoud oorspronkelijke `Host`, verifieer `X-Forwarded-Proto=https`;
- schrijf de geverifieerde client-IP in `X-Forwarded-For`, **append nooit
  blind een onbetrouwbare client-aangeleverde XFF**;
- max. request/bodygrootte, redelijke read/connect timeouts, proxy-brede
  gelijktijdigheids-/verzoeklimieten en een 429-response met `Retry-After`
  waar ondersteund;
- schakel proxycaching van fout- en locatiegegevens uit tot verse/stale
  semantiek is gecontroleerd;
- configureer DNS A/AAAA en router/NAT voor TCP 443 uitsluitend naar reverse
  proxy; publiceer **niet** TCP 5088 of 5432 extern.

**Compatibiliteit:** een NAS ingebouwde reverse proxy kan Nginx gebruiken maar
niet alle Nginx-directives als UI-optie aanbieden. De configuratie moet daarom
op de werkelijke NAS/proxy worden toegepast en gecontroleerd, niet als los
onbeheerd extra Nginx-proces gestart.

## Operator-runbook (expliciet deploymentakkoord vereist)

1. Controleer het actuele NAS-proxyproduct, publieke DNS, bestaand
   certificaat-/routerbeheer en of er al een rule voor dezelfde host bestaat.
2. Controleer rollback: bestaande Compose-config, gekozen API image tag,
   lokale `.env` veilig bewaren; maak/verifieer recente DB-backup. Geen
   `down -v`, geen databasereset.
3. Richt een HTTPS-rule met bovenstaande host en upstream in.
   Laat de API nog niet onbeperkt publiek bereikbaar totdat rate limits,
   trusted-proxy-instellingen, TLS en abusegrenzen getest zijn.
4. Deploy alleen een **gepubliceerde en gevalideerde main API-image SHA** nadat
   afzonderlijk expliciet akkoord voor deploy is gegeven. Houd collector- en
   DB-tags ongewijzigd.
5. Test vanaf een extern netwerk (niet alleen de NAS):
   ```sh
   curl -i https://chargewatch-api.vanhinthem.nl/api/v1/data-status
   curl -i 'https://chargewatch-api.vanhinthem.nl/api/v1/locations?limit=1'
   ```
   Controleer de laatste polltijd, TLS-certificaat, HTTP-content, 404,
   fout-/rate-limitgedrag en ontbreken van server-/DB-credentials.
6. Verifieer dat directe verbinding vanaf buiten naar poort 5088 en 5432
   niet mogelijk is, dat gemanipuleerde `X-Forwarded-For` de limiter niet
   kan omzeilen en dat herhaalde requests geen NAS-/DB-overbelasting geven.
7. Maak vervolgens de Android-build met exact deze HTTPS-origin en valideer
   read-only tarieven, experimentele historische grafieken en NDW-fallback
   op het toestel. De fallback zelf is een afzonderlijke feature.
8. Rollback: verwijder/deactiveer alleen de nieuwe proxyregel en herstel de
   vooraf vastgelegde API-image tag. Laat DB/collector intact.

## Releasegates

**Voor externe toegang:** expliciete beheerderstoestemming voor wijziging van
DNS/proxy/router; geldige TLS, afgeschermde DB/5088, verdedigbare proxy- en
app-limieten, correcte bron-IP, requesttimeouts en geslaagde externe smoke
checks. Als een van deze controles niet slaagt, de host niet publiek openzetten.

**Voor brede publieke Android-release:** Google Play Integrity Standard requests
en backend-verificatie met gebonden requestHash, signing-/packagechecks,
misbruik-/replaybeleid, monitoring en loadtests. Verifieer ook NDW- en
kaartdata-gebruiksvoorwaarden. Er is bewust nog geen gedeeld hardcoded
Android-secret bedacht.

**Status van dit document:** deploymenthandleiding/ontwerp; er is hier geen
DNS, router, proxy, NAS of Docker-runtime daadwerkelijk gewijzigd.
