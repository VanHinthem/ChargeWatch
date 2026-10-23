#!/usr/bin/env python3
"""Generate two independent historical sequences; no existing fixture is changed."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import gzip
import json

root = Path(__file__).resolve().parents[1]
source = json.loads((root / "tests/fixtures/ndw_schietspoel_2026-10-22.json").read_text(encoding="utf-8"))
out = root / "data/sprint3-finalize-fixture"
out.mkdir(parents=True, exist_ok=True)
origin = datetime(2026, 10, 20, 8, 0, tzinfo=timezone.utc)

for scenario, minutes in {
    "low": [55, 65],
    "full": list(range(0, 70, 5)),
}.items():
    for number, minute in enumerate(minutes, 1):
        timestamp = (origin + timedelta(minutes=minute)).strftime("%Y-%m-%dT%H:%M:%SZ")
        loc = json.loads(json.dumps(source["location"]))
        loc.update(country_code="ZZ", party_id="CW",
                   id=f"sprint3-finalize-{scenario}", last_updated=timestamp)
        for index, evse in enumerate(loc["evses"], 1):
            evse.update(uid=f"sprint3-finalize-{scenario}-evse-{index}",
                        evse_id=f"ZZ*CW*EFINAL{scenario.upper()}{index}",
                        status="AVAILABLE", last_updated=timestamp)
        with gzip.open(out / f"{scenario}-{number:02d}.json.gz", "wt", encoding="utf-8") as stream:
            json.dump([loc], stream, ensure_ascii=False)
    print(f"{scenario}: {len(minutes)} snapshots, UTC {minutes[0]}..{minutes[-1]} min after {origin.isoformat()}")
print("Output:", out)
