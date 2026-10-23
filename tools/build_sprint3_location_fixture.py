#!/usr/bin/env python3
"""Synthetic location snapshots to validate joint availability and uncertainty."""
import gzip
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[1]
fixture = json.loads((root / "tests/fixtures/ndw_schietspoel_2026-10-22.json").read_text(encoding="utf-8"))
out = root / "data/sprint3-location-fixture"
out.mkdir(parents=True, exist_ok=True)
origin = datetime(2026, 10, 20, 8, 0, tzinfo=timezone.utc)
for scenario in ("available", "unknown"):
    for seq, minutes in enumerate(range(0, 70, 5), start=1):
        timestamp = (origin + timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
        loc = json.loads(json.dumps(fixture["location"]))
        loc.update(country_code="ZZ", party_id="CW",
                   id=f"sprint3-location-{scenario}", last_updated=timestamp)
        for i, evse in enumerate(loc["evses"], 1):
            evse.update(uid=f"sprint3-location-{scenario}-evse-{i}",
                        evse_id=f"ZZ*CW*ELOCATION{scenario.upper()}{i}",
                        last_updated=timestamp,
                        status=("UNKNOWN" if scenario == "unknown" and i == 2 else "AVAILABLE"))
        with gzip.open(out / f"{scenario}-{seq:02d}.json.gz", "wt", encoding="utf-8") as stream:
            json.dump([loc], stream, ensure_ascii=False)
print("Prepared 14 aligned snapshots each for available and unknown scenarios:", out)
