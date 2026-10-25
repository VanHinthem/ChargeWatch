# NDW EVSE validation — uitvoeren met echte brondata

NDW publiceert een GeoJSON-feed en een volledige OCPI-locatiefeed via
https://opendata.ndw.nu/. De vergelijking moet **eerst** de daadwerkelijk
aanwezige veldnamen en de stabiele identificatie per locatie bevestigen.

## Windows PowerShell (in de repository-root)

```powershell
New-Item -ItemType Directory -Force .local/ndw | Out-Null
Invoke-WebRequest 'https://opendata.ndw.nu/charging_point_locations.geojson.gz' -OutFile '.local/ndw/locations.geojson.gz'
Invoke-WebRequest 'https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz' -OutFile '.local/ndw/locations_ocpi.json.gz'
python scripts/validate_ndw_evse.py .local/ndw/locations.geojson.gz .local/ndw/locations_ocpi.json.gz --out .local/ndw/evse-validation.json
```

Beide feeds wijzigen frequent. Download ze direct na elkaar en controleer of
de response-publicatietijden overeenkomen; anders zijn verschillen door de
tijd niet uit te sluiten.

Het script **weigert impliciete matching**: als NDW geen expliciete
`country_code`/`party_id`/`source_id` in GeoJSON levert, meldt het nul
matches en stopt het. Dat betekent niet dat er nul gekoppelde locaties zijn.
De ID-regel moet op echte NDW-records worden onderzocht; maak geen
nauwkeurigheidsclaims voordat dat gevalideerd is.

## Verwachte controle

- Aantal gematchte locaties en aantal niet-gematchte locaties
- Verdeling van enkele of meerdere connectorcategorieën
- Verschillen tussen categorieparen en unieke EVSE-UID-statussen
- Unknown-statussen afzonderlijk van beschikbaar/niet beschikbaar
- Handmatige controle van een kleine representatieve Oss-steekproef

**Niet** naar GitHub committen: de complete landelijke bronbestanden en
ongeanonimiseerde locatiegegevens zijn onderzoeksinput. Alleen
geanonimiseerde fixtures, aggregaatresultaten en reproduceerbare logica
mogen als PR-reviewartefact worden toegevoegd.
