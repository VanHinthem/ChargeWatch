#!/usr/bin/env python3
"""Build isolated, synthetic OCPI status sequence for Sprint 3 hour accumulation."""
import gzip
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = json.loads((root / "tests/fixtures/ndw_schietspoel_2026-10-22.json").read_text(encoding="utf-8"))
out = root / "data/sprint3-hour-fixture"
out.mkdir(parents=True, exist_ok=True)
# These keys never overlap with real NDW keys, nor with the Sprint 1 test fixture.
timestamps = ("2026-10-23T10:55:00Z", "2026-10-23T11:05:00Z", "2026-10-23T11:10:00Z")
for number, timestamp in enumerate(timestamps, 1):
    loc = json.loads(json.dumps(source["location"]))
    loc["country_code"] = "ZZ"
    loc["party_id"] = "CW"
    loc["id"] = "sprint3-hour-synthetic"
    loc["last_updated"] = timestamp
    for index, evse in enumerate(loc["evses"], 1):
        evse["uid"] = f"sprint3-hour-evse-{index}"
        evse["evse_id"] = f"ZZ*CW*ETEST{index}"
        evse["status"] = "AVAILABLE"
        evse["last_updated"] = timestamp
    with gzip.open(out / f"locations-{number}.json.gz", "wt", encoding="utf-8") as stream:
        json.dump([loc], stream, ensure_ascii=False)
print("Created three isolated snapshots in", out)
