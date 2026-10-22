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

## Landelijke tariefdekking en ID-stabiliteit tussen snapshots

**Bewaar de oorspronkelijke download.** `ndw_discover.py` schrijft bestanden in de opgegeven outputmap over. Maak daarom eerst een tweede snapshot in een andere map:

```powershell
python tools/ndw_discover.py --output data/ndw-compare
python tools/ndw_compare.py
```

Dit vergelijkt de oorspronkelijke bestanden onder `data/ndw-discovery/` met de tweede download onder `data/ndw-compare/`. Resultaat: `data/ndw-compare/compare_report.json`. Alle bestanden onder beide subdirectories worden door `data/.gitignore` genegeerd.

De vergelijking rapporteert per snapshot:
- aantallen locaties/EVSE's/connectors/tarieven en dubbele samengestelde bronkeys;
- connectors zonder `tariff_ids`, en iedere referentie als `resolved`, `missing` of `ambiguous` op basis van `country_code + party_id + tariff ID`;
- gedeelde en toegevoegde/verdwenen locatiekeys, gedeelde en toegevoegde/verdwenen EVSE-keys op basis van locatiekey + `uid`, en wijzigingen in `evse_id` onder een gedeelde sleutel.

**Interpretatie:** ontbreken in één momentopname bewijst geen echte fysieke verwijdering. Stabiele keys tussen twee snapshots bewijzen niet dat ze permanent stabiel zijn. `resolved` betekent alleen dat de bronverwijzing exact één tariefobject vindt; het bewijst niet dat het tarief contractueel van toepassing is op een specifieke laadpas. Gzip-snapshots kunnen op verschillende tijdstippen zijn gepubliceerd, en de analyse leest beide volledige JSON-arrays in geheugen (alleen onderzoek).
