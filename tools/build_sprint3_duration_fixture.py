#!/usr/bin/env python3
"""Test a valid EVSE run followed by a status transition and a long gap."""
import gzip,json
from pathlib import Path
from datetime import datetime,timedelta,timezone
root=Path(__file__).resolve().parents[1]
base=json.loads((root/"tests/fixtures/ndw_schietspoel_2026-10-22.json").read_text(encoding="utf-8"))["location"]
out=root/"data/sprint3-duration-fixture"
out.mkdir(parents=True,exist_ok=True)
origin=datetime(2026,10,20,12,0,tzinfo=timezone.utc)
# first two consecutive AVAILABLE snapshots; transition to CHARGING at 12:10;
# another CHARGING at 12:15, then a 30-minute gap to 12:45.
for index,(minutes,status) in enumerate([(0,"AVAILABLE"),(5,"AVAILABLE"),(10,"CHARGING"),(15,"CHARGING"),(45,"CHARGING")],1):
    loc=json.loads(json.dumps(base))
    stamp=(origin+timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
    loc.update(country_code="ZZ",party_id="CW",id="sprint3-duration-test",last_updated=stamp)
    for i,e in enumerate(loc["evses"],1):
        e.update(uid=f"sprint3-duration-evse-{i}",evse_id=f"ZZ*CW*EDURATION{i}",
                 last_updated=stamp,status=status)
    with gzip.open(out/f"status-{index:02d}.json.gz","wt",encoding="utf-8") as fp:
        json.dump([loc],fp)
print("Five deterministic duration snapshots:",out)
