# Gzip voor read-only JSON API-responses — 5 november 2026

**Status:** afzonderlijke API-PR (niet gemerged of gedeployed). Android PR #123 blijft onafhankelijk en ongemerged.

## Scope

- ASP.NET Core 10 `ResponseCompression` middleware, uitsluitend voor `application/json`.
- Eén provider: **gzip**, met `CompressionLevel.Fastest`. Geen nieuwe NuGet-dependencies, DB-migraties, collector- of Android-wijzigingen.
- Compressie uitsluitend wanneer de client `Accept-Encoding: gzip` verstuurt; zonder gzip blijft de ongewijzigde JSON-response beschikbaar.
- Ondersteunt JSON-resultaten van alle huidige endpoints, waaronder `/api/v1/locations`, `/api/v1/map/viewport`, `/api/v1/map/overview`, `/api/v1/map/overview/cell`, individuele details en `POST /api/v1/locations/by-ndw-id/batch`.
- `Vary: Accept-Encoding` voor HTTP-caches; bestaande `Cache-Control: no-store` op kaart-overview en cell blijven intact.
- `EnableForHttps=true` voor de TLS-verbinding met de publieke API (of TLS-proxy); beveiligingsoverweging: alleen publieke read-only JSON-data, geen gebruikersspecifieke geheimen/cookies of reflectie van privé-tokens in de payload. Bij toekomstige gepersonaliseerde endpoints compressie en BREACH-risico opnieuw beoordelen.

## NAS-benchmark als motivatie (gemeten vóór implementatie)

20 locaties: zonder historische statistieken `63,4 KiB` ongecomprimeerd → `8,0 KiB` bij Python gzip niveau 1. Met statistieken `3.117,7 KiB` → `120,8 KiB` (**96,1% reductie**). De Python-compressietijd was voor het volledige resultaat circa `9,2 ms` mediane CPU-tijd. Dit is **geen** gemeten werkelijke .NET/Kestrel-HTTP-latency, geen gemiddelde mobiele overdrachtstijd en nog geen Android-decompressiemeting.

De nieuwe API-PR verifieert in CI de werkelijke HTTP-`Content-Encoding: gzip`, `Vary`, JSON-pariteit, kortere payloads voor betekenisvolle JSON en de bestaande `no-store`-contracten. HTTP-timing en decompressiebelasting op Samsung zijn afzonderlijke acceptatiemetingen.

## Deploy/acceptatie

1. Alle API- en workflowchecks op de draft-PR groen. Geen automatische merge.
2. Na expliciet akkoord squash-merge; de NAS-deployment blijft een zelfstandige handeling.
3. Controleer na NAS-update zowel **intern** (`http://api:8080`) als via de publieke HTTPS-proxy:
   ```bash
   curl -sS -D - -o /dev/null -H 'Accept-Encoding: gzip' 'https://chargewatch-api.vanhinthem.nl/api/v1/locations?limit=20'
   ```
   Verwacht `Content-Encoding: gzip` bij de publieke route als de reverse proxy de gzip-negotiation doorlaat. Test ook met `Accept-Encoding: identity`: dan **geen** gzip. Let op: een eventuele reverse proxy kan compressie toevoegen of afhandelen; onderscheid bron en proxy bij metingen.
4. Vergelijk responsegrootte en latency op batch, kaart-overview en cluster-cell; controleer Android/OkHttp op kaartbewegingen, favorieten en details. Alleen productieklare PR deployen na testen.

**Kleine JSON-responses:** ASP.NET Core's standaard streaming middleware heeft geen grootte-threshold. Ook kleine `application/json`-antwoorden kunnen gecomprimeerd worden wanneer de client gzip toestaat. Een custom bufferlaag enkel om een drempel te implementeren is bewust niet toegevoegd: die zou geheugen, vertraging en complexiteit verhogen. Evalueer kleine responses apart als dit in de praktijk relevant wordt.

**Geen cachewijziging:** gzip verkleint de netwerkbytes, maar wijzigt niet de frequentie van dataverzoeken of de reeds afgesproken tweelaagse Android-cache in PR #123.
