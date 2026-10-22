# NDW-discoverytool (Sprint 0)

Vereist Python 3.10+; alleen de standaardbibliotheek. Er worden geen credentials gebruikt.

Vanaf de repository-root:

```powershell
python tools/ndw_discover.py
```

De tool downloadt de huidige publieke landelijke OCPI gzip-bestanden en schrijft onder `data/ndw-discovery/`:

- `locations.json.gz` en `tariffs.json.gz` — ruwe momentopnamen (niet committen).
- `report.json` — aantallen, geobserveerde EVSE-statussen, bronmetadata, SHA256 en veldnamen.
- `schietspoel_locations.json` — gevonden Schietspoel-objecten (originele bronvelden).
- `schietspoel_tariffs.json` — tariefreferenties en gevonden gekoppelde objecten.

Opnieuw dezelfde gedownloade bestanden analyseren zonder HTTP-verzoek:

```powershell
python tools/ndw_discover.py --offline
```

Gebruik `--output pad` voor een andere directory. Bij een onbekende JSON-envelope faalt het script expliciet; pas de parser pas aan na inspectie van de echte export. De complete landelijke JSON wordt in geheugen geladen: dit is een tijdelijke **onderzoeks**tool, niet het ontwerp van de uiteindelijke streaming importer.

**Validatie:** controleer `report.json` en Schietspoel-bestanden. Tariff-linkresolutie gebruikt voorlopig landcode + party + tarief-ID van de locatie, en moet tegen de werkelijke feed getoetst worden. Neem alleen een paar representatieve, geverifieerde gegevens op in Git; er zijn nog geen fixtures gecommit.

De tool uploadt niets en schrijft alleen naar de gekozen outputdirectory.

Voor HTTP caching/304-check (zonder volledige downloads):

```powershell
python tools/check_ndw_http_cache.py
```

De check is vanuit de agentomgeving niet online uitvoerbaar door DNS-beperkingen; een lokale run is nodig.
