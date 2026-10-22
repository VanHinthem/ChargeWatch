#!/usr/bin/env python3
"""Compare two downloaded NDW OCPI gzip snapshots. Python 3.10+ stdlib only."""
import argparse
from collections import Counter, defaultdict
import gzip
import json
from pathlib import Path


def read(directory, name):
    path = Path(directory) / (name + ".json.gz")
    with gzip.open(path, "rt", encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, list):
        raise ValueError(f"{path}: expected JSON array")
    return value


def key(obj):
    return (str(obj.get("country_code") or ""), str(obj.get("party_id") or ""), str(obj.get("id") or ""))


def snapshot(directory):
    locations = read(directory, "locations")
    tariffs = read(directory, "tariffs")
    tariff_keys = Counter(key(t) for t in tariffs)
    location_keys, evse_keys, links, statuses = Counter(), Counter(), Counter(), Counter()
    external_ids = defaultdict(set)
    connectors = 0
    no_tariff = 0
    for loc in locations:
        lk = key(loc)
        location_keys[lk] += 1
        for evse in loc.get("evses") or []:
            ek = (*lk, str(evse.get("uid") or ""))
            evse_keys[ek] += 1
            if evse.get("evse_id"):
                external_ids[ek].add(str(evse["evse_id"]))
            statuses[str(evse.get("status") or "<missing>")] += 1
            for connector in evse.get("connectors") or []:
                connectors += 1
                references = connector.get("tariff_ids") or []
                if not references:
                    no_tariff += 1
                for tariff_id in references:
                    matches = tariff_keys[(lk[0], lk[1], str(tariff_id))]
                    links["total"] += 1
                    links["missing" if matches == 0 else "resolved" if matches == 1 else "ambiguous"] += 1
    return dict(locations=location_keys, evses=evse_keys, tariffs=tariff_keys,
                external_ids=external_ids, statuses=statuses, links=links,
                connectors=connectors, connectors_without_tariff=no_tariff)


def describe(data):
    return {
        "locations": sum(data["locations"].values()),
        "evses": sum(data["evses"].values()),
        "tariffs": sum(data["tariffs"].values()),
        "connectors": data["connectors"],
        "connectors_without_tariff": data["connectors_without_tariff"],
        "duplicate_location_keys": sum(v-1 for v in data["locations"].values() if v > 1),
        "duplicate_evse_keys": sum(v-1 for v in data["evses"].values() if v > 1),
        "duplicate_tariff_keys": sum(v-1 for v in data["tariffs"].values() if v > 1),
        "tariff_links": dict(data["links"]),
        "statuses": dict(sorted(data["statuses"].items())),
    }


def compare(baseline, current):
    old, new = snapshot(baseline), snapshot(current)
    old_l, new_l = set(old["locations"]), set(new["locations"])
    old_e, new_e = set(old["evses"]), set(new["evses"])
    shared_e = old_e & new_e
    changed_external = sum(
        1 for k in shared_e
        if old["external_ids"][k] and new["external_ids"][k]
        and old["external_ids"][k] != new["external_ids"][k]
    )
    return {
        "baseline_directory": str(baseline), "current_directory": str(current),
        "baseline": describe(old), "current": describe(new),
        "identity_comparison": {
            "locations_shared": len(old_l & new_l),
            "locations_only_baseline": len(old_l - new_l),
            "locations_only_current": len(new_l - old_l),
            "evses_shared": len(shared_e),
            "evses_only_baseline": len(old_e - new_e),
            "evses_only_current": len(new_e - old_e),
            "shared_evse_keys_with_changed_external_evse_id": changed_external,
        },
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", default="data/ndw-discovery")
    p.add_argument("--current", default="data/ndw-compare")
    p.add_argument("--output", default="data/ndw-compare/compare_report.json")
    a = p.parse_args()
    if Path(a.baseline).resolve() == Path(a.current).resolve():
        p.error("Baseline and current directories must differ")
    result = compare(a.baseline, a.current)
    out = Path(a.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"Wrote {out}")
