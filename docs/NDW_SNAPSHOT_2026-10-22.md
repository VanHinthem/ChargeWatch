# NDW landelijke momentopname — 22 oktober 2026

**Herkomst:** gebruikersrun van `python tools/ndw_discover.py`; aangeleverd `report.json`.
**Bewijsniveau:** werkelijke nationale gzip-downloads verwerkt door analysetool; de individuele ruwe bronobjecten zijn nog niet aangeleverd ter inspectie. Dit document legt de inhoud van het rapport vast, niet een onafhankelijk herhaalde download.

## Bronmetadata

| | Locations | Tariffs |
|---|---|---|
| URL | https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz | https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz |
| Download UTC | 2026-10-22 12:22:06.983495 | 2026-10-22 12:22:10.126317 |
| Last-Modified | Thu, 22 Oct 2026 12:22:00 GMT | Thu, 22 Oct 2026 10:02:00 GMT |
| Content-Length/werkelijke bytes | 18.368.879 | 4.143.448 |
| ETag | `"118496f-65e6ce9184aeb"` | `"3f3958-65e6af46722db"` |
| SHA256 | `2d9c0ebb8aa65d2f660bfa76e164ab7e58cda97fe262c76043c6e046494f1a29` | `42ccfb764807be8e77e9f90ac2f30a86524e94c7b727a59e1ecde0f730276884` |
| JSON root | array (`list`) | array (`list`) |

Dit bevestigt dat beide bestanden een direct parsebare JSON-array bevatten en dat deze responses ETag en Last-Modified leveren. Het **effect** van If-None-Match/If-Modified-Since is nog niet getest.

## Datasetomvang

- 80.777 locaties
- 203.114 EVSE's
- 203.807 connectoren
- 101.209 tariefobjecten
- 15.706 unieke vanuit connectoren gerefereerde tarief-ID-strings (geen uitspraak over landelijke resolutiegraad).

## Alle geobserveerde EVSE-statussen

| Status | Aantal |
|---|---:|
| AVAILABLE | 138.099 |
| BLOCKED | 180 |
| CHARGING | 45.848 |
| INOPERATIVE | 1.269 |
| OUTOFORDER | 2.293 |
| PLANNED | 22 |
| REMOVED | 5.196 |
| RESERVED | 3 |
| UNKNOWN | 10.204 |
| **Totaal** | **203.114** |

Import- en statistiekontwerp moet deze waarden expliciet behandelen. De momentopname bewijst niet dat elke status met dezelfde frequentie voorkomt in elke afzonderlijke snapshot.

## Werkelijke veldnamen

- Location: `address, charging_when_closed, city, coordinates, country, country_code, directions, energy_mix, evses, facilities, id, images, last_updated, name, opening_times, operator, owner, parking_type, party_id, postal_code, publish, publish_allowed_to, related_locations, state, suboperator, time_zone`.
- EVSE: `capabilities, connectors, coordinates, directions, evse_id, floor_level, images, last_updated, parking_restrictions, physical_reference, status, status_schedule, uid`.
- Connector: `format, id, last_updated, max_amperage, max_electric_power, max_voltage, power_type, standard, tariff_ids, terms_and_conditions`.
- Tariff: `country_code, currency, elements, end_date_time, energy_mix, id, last_updated, max_price, min_price, party_id, start_date_time, tariff_alt_text, tariff_alt_url, type`.

De aanwezigheid in de landelijke veld-unie is **geen garantie** dat het veld in ieder record ingevuld is.

## Schietspoel 18, Oss

- Eén Location match gevonden via adres/naamzoeking.
- Twee EVSE-objecten in dat resultaat leverden elk één connector met eenzelfde tarief-ID `2000025394-2000011292`.
- Beide tarievenverwijzingen werden één-op-één geresolveerd met `country_code + party_id + tariff_id` volgens de analysetool.
- In deze snapshot gebruikte EVSE-UIDs:
  - `8807cd9e-960e-4d8c-ac3d-6c29938963a2`
  - `0722d0f7-effe-490b-af5e-ec2fa66729f3`
- Connector-IDs:
  - `b13b9d6c-5126-458a-a897-272e219d0f9b`
  - `a3e7bb67-2701-49a4-bfe4-e1e90b1c5fe9`

Let op: deze UUID's zijn niet dezelfde strings als de eerder in een andere bron waargenomen `NL*NUO*...` EVSE-identificaties. Vergelijk `uid` én `evse_id` in het bronrecord voordat stable-id-keuzes worden vastgezet.

## Open verificatie

1. Inspecteer `schietspoel_locations.json` en `schietspoel_tariffs.json` uit dezelfde run: daadwerkelijke `evse_id`, `status`, `last_updated`, connector-details en `elements[].price_components`.
2. Controleer tariefresolutie buiten Schietspoel en dekking/NULLs op representatieve records.
3. Beoordeel updategedrag en stabiele keys over minimaal twee snapshots.
4. Test conditionele HTTP-requests; de header-aanwezigheid alleen bewijst nog geen 304-responses.
5. Bewaak bronlicentie en attributie, en voeg compacte, niet-verzonnen bronfixtures toe voor een herhaalbare controle.


## Schietspoel-bronobjecten gecontroleerd (door gebruiker aangeleverd)

De twee JSON-extracties uit deze snapshot bevestigen:

- Location: `NL / NUO / f08bed59-be4b-42d4-8b9b-423f03f82bc6`, Schietspoel 18, 5348 JH Oss; operator Vattenfall InCharge; coördinaten 51.767547, 5.529763; 24/7.
- EVSE `NL*NUO*EALF*0005002*1`: UID `8807cd9e-960e-4d8c-ac3d-6c29938963a2`, `AVAILABLE`, laatst bijgewerkt `2026-10-21T17:55:39Z`.
- EVSE `NL*NUO*EALF*0005002*2`: UID `0722d0f7-effe-490b-af5e-ec2fa66729f3`, `AVAILABLE`, laatst bijgewerkt `2026-10-22T08:57:01Z`.
- Per EVSE één socket: `IEC_62196_T2`, `AC_3_PHASE`, 11040 watt, dezelfde tariefreferentie.
- Voor beide connectors resolveert `NL/NUO/2000025394-2000011292` naar een tarief van het type `REGULAR`, valuta EUR, bijgewerkt `2026-10-22T00:25:24Z`.
- Prijscomponent: `ENERGY`, `price=0.2691`, `vat=21%`, `step_size=1`; startrestrictie `2026-01-01`. Rekenkundig inclusief btw: €0,325611/kWh, afgerond **€0,33/kWh**; dit sluit aan op de Nederlandse `tariff_alt_text` (Per kWh: 0.33 EUR). Of de onderliggende contractprijs/AFIR-presentatieregels standaard exclusief btw zijn blijft relevant voor algemene tariefimplementatie.
- **Belangrijk:** EVSE-`uid` is hier een UUID, terwijl `evse_id` de herkenbare externe `NL*NUO*...`-waarde bevat. Niet uitwisselbaar behandelen.
- Individuele EVSE-status-actualiteit is niet hetzelfde als locatietijdstempel of downloadsnapshot; status van EVSE 1 is ouder dan die van EVSE 2.

Kleine, uit **werkelijke bronobjecten geselecteerde** velden zijn opgenomen in `tests/fixtures/ndw_schietspoel_2026-10-22.json`. Dit is een bewust ingekorte fixture, **niet** een ongewijzigd volledig OCPI-record. Een gerichte contractcheck staat in `tests/check_ndw_fixture.py`.

De fixture is als JSON geparsed en de twee tariefrelaties/btw-berekening zijn gecontroleerd. Het Python-checkscript is nog **niet uitgevoerd** op deze agentomgeving; dus geen groene Python-test of CI-resultaat claimen.
