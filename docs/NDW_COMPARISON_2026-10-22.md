# NDW nationale vergelijking — 22 oktober 2026

Bron: lokaal uitgevoerde `tools/ndw_compare.py`, door gebruiker aangeleverde `compare_report.json`. De tijdstempels van de tweede snapshot staan niet in dit vergelijkingsrapport.

## Beide snapshots
- 80.777 locaties, 203.114 EVSE's, 203.807 connectoren en 101.209 tarieven.
- 80.777 gedeelde locatiekeys, 203.114 gedeelde EVSE-keys; geen toegevoegde of verdwenen keys.
- Geen dubbele samengestelde locatie-, EVSE- of tariefkeys.
- Geen gewijzigde niet-lege externe `evse_id` bij gedeelde EVSE-keys.

## Landelijke tariefdekking
- 24.566 van 203.807 connectoren zonder `tariff_ids` (12,05%).
- 179.241 connector-tariefverwijzingen; 174.161 (97,17%) verwijzen naar exact één tarief.
- 5.080 verwijzingen (2,83%) zonder match, geen ambigue matches.
- Deze percentages hebben verschillende noemers en mogen niet worden opgeteld.
- Ontbrekend of niet-resolverend tarief betekent onbekende prijs, niet prijs nul.

## Statusaggregaten baseline naar tweede snapshot
- AVAILABLE: 138.099 naar 138.209 (+110).
- CHARGING: 45.848 naar 45.747 (-101).
- BLOCKED: 180 naar 181 (+1).
- OUTOFORDER: 2.293 naar 2.281 (-12).
- UNKNOWN: 10.204 naar 10.206 (+2).
- INOPERATIVE: 1.269; REMOVED: 5.196; PLANNED: 22; RESERVED: 3 blijven gelijk.

Verschillen in statusaggregaten bewijzen geen individuele EVSE-overgangen en vormen geen sessiehistorie.

## Ontwerpconclusies
1. Gebruik location identity `country_code + party_id + id`, EVSE identity locatiekey + `uid`, en tariefidentity `country_code + party_id + id`. Bewaar `evse_id` apart.
2. Bewaak duplicate keys, missing tariff links, ontbrekende tarieven en individuele statusversheid.
3. Beschouw een missende record niet automatisch als verwijdering bij incomplete imports.
4. Twee snapshots bewijzen alleen stabiliteit binnen dit waargenomen interval, geen levenslange key-stabiliteit.
5. Voor publieke distributie blijven licentie- en attributievoorwaarden expliciet te controleren.
