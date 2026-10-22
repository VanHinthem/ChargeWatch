import json
from pathlib import Path

data=json.loads((Path(__file__).parent/"fixtures"/"ndw_schietspoel_2026-10-22.json").read_text())
loc=data["location"]
assert loc["city"]=="Oss"
assert {e["evse_id"] for e in loc["evses"]}=={"NL*NUO*EALF*0005002*1","NL*NUO*EALF*0005002*2"}
lookup={(t["country_code"],t["party_id"],t["id"]):t for t in data["tariffs"]}
for evse in loc["evses"]:
    for connector in evse["connectors"]:
        assert connector["max_electric_power"]==11040
        for ident in connector["tariff_ids"]:
            tariff=lookup[(loc["country_code"],loc["party_id"],ident)]
            component=tariff["elements"][0]["price_components"][0]
            assert component["type"]=="ENERGY"
            assert round(component["price"]*(1+component["vat"]/100),2)==0.33
print("Fixture check passed")
