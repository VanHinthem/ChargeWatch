#!/usr/bin/env python3
"""Synthetic national-scale OCPI fixture. No poll history or external network."""
import gzip
import json
import sys

path=sys.argv[1]
location_count=int(sys.argv[2])
evse_count=int(sys.argv[3])
assert location_count>0 and evse_count>=location_count
with gzip.open(path,"wt",encoding="utf-8",compresslevel=1) as stream:
    stream.write("[")
    next_evse=0
    for loc in range(location_count):
        item={"country_code":"NL","party_id":"TST","id":f"LOC{loc:06}",
              "address":"Synthetic","city":"Test","evses":[]}
        for _ in range(evse_count//location_count+(loc<evse_count%location_count)):
            next_evse+=1
            item["evses"].append({"uid":f"EVSE{next_evse:07}",
                "status":"AVAILABLE" if next_evse%3 else "CHARGING",
                "connectors":[{"id":"1","standard":"IEC_62196_T2","power_type":"AC_3_PHASE"}]})
        if loc: stream.write(",")
        stream.write(json.dumps(item,separators=(",",":")))
    stream.write("]")
assert next_evse==evse_count
print(f"Generated {location_count} locations, {evse_count} EVSE in {path}",flush=True)
