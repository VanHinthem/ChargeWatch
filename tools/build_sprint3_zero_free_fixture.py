#!/usr/bin/env python3
"""Independent location zero-free transition, recovery and gap validation snapshots."""
import gzip,json
from datetime import datetime,timedelta,timezone
from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=json.loads((root/"tests/fixtures/ndw_schietspoel_2026-10-22.json").read_text(encoding="utf-8"))["location"]
output=root/"data/sprint3-zero-free-fixture"
output.mkdir(parents=True,exist_ok=True)
origin=datetime(2026,10,20,14,0,tzinfo=timezone.utc)
for seq,(minute,status) in enumerate(((0,"CHARGING"),(5,"CHARGING"),(10,"AVAILABLE"),(15,"CHARGING"),(20,"CHARGING"),(50,"CHARGING")) ,1):
    loc=json.loads(json.dumps(base))
    stamp=(origin+timedelta(minutes=minute)).strftime("%Y-%m-%dT%H:%M:%SZ")
    loc.update(country_code="ZZ",party_id="CW",id="sprint3-zero-free-test",last_updated=stamp)
    for i,e in enumerate(loc["evses"],1):
        e.update(uid=f"sprint3-zero-free-evse-{i}",evse_id=f"ZZ*CW*EZEROFREE{i}",last_updated=stamp,status=status)
    with gzip.open(output/f"state-{seq:02d}.json.gz","wt",encoding="utf-8") as stream:
        json.dump([loc],stream)
print("Prepared 6 isolated snapshots:",output)
