#!/usr/bin/env python3
"""Compare NDW GeoJSON category counts to uniquely identified OCPI EVSEs.

Usage:
 python3 scripts/validate_ndw_evse.py charging_point_locations.geojson.gz charging_point_locations_ocpi.json.gz

This analysis makes NO assumption that a connector category is a unique EVSE.
Input files must be captured close in time; output never declares a counting rule
safe merely because some observations happen to match.
"""
import argparse
import gzip
import json
from collections import Counter, defaultdict
from pathlib import Path


def read_json(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        return json.load(f)


def ocpi_locations(value):
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        for key in ("data", "locations", "results"):
            if isinstance(value.get(key), list):
                return value[key]
    raise ValueError("Unknown OCPI root shape (expected locations list or data/locations/results)")


def key(country, party, location):
    if not all(isinstance(v, str) and v for v in (country, party, location)):
        return None
    return (country.upper(), party.upper(), location)


def ocpi_key(loc):
    return key(loc.get("country_code"), loc.get("party_id"), loc.get("id"))


def geo_key(feature):
    properties = feature.get("properties") or {}
    # NDW GeoJSON has no standalone party_id or source_id. The feature id
    # appears to be country-party-location; validate against a real pair.
    feature_id = feature.get("id")
    if not isinstance(feature_id, str):
        return None
    components = feature_id.split("-", 2)
    if len(components) != 3:
        return None
    country, party, location = components
    if properties.get("country") and properties["country"].upper() != country.upper():
        return None
    return key(country, party, location)


def count_unique_evses(location):
    seen = {}
    for evse in location.get("evses") or []:
        uid = evse.get("uid")
        if not isinstance(uid, str) or not uid:
            continue
        # Do not count duplicated EVSE records twice; flag conflicting statuses.
        status = str(evse.get("status") or "UNKNOWN").upper()
        if uid in seen and seen[uid] != status:
            seen[uid] = "CONFLICT"
        else:
            seen[uid] = status
    states = Counter(seen.values())
    return {
        "total": len(seen),
        "available": states["AVAILABLE"],
        "unknown": sum(states[x] for x in ("UNKNOWN", "CONFLICT")),
        "statuses": dict(states),
    }


def category_counts(feature):
    rows = (feature.get("properties") or {}).get("availabilities") or []
    pairs = []
    for row in rows:
        a, t = row.get("available"), row.get("total")
        if isinstance(a, int) and isinstance(t, int) and 0 <= a <= t and t > 0:
            pairs.append((a, t))
    return pairs


def compare(geojson, ocpi):
    ocpi_by_key = {}
    for loc in ocpi_locations(ocpi):
        if not isinstance(loc, dict):
            continue
        k = ocpi_key(loc)
        if k:
            ocpi_by_key[k] = loc
    stats = Counter()
    examples = []
    for feature in geojson.get("features", []):
        k = geo_key(feature)
        if not k or k not in ocpi_by_key:
            stats["unmatched_geojson"] += 1
            continue
        stats["matched"] += 1
        evses = count_unique_evses(ocpi_by_key[k])
        pairs = category_counts(feature)
        if not pairs:
            stats["no_valid_categories"] += 1
            continue
        if evses["total"] == 0:
            stats["no_identified_evses"] += 1
            continue
        if evses["unknown"]:
            stats["ocpi_unknown_status"] += 1
            continue
        candidate = pairs[0] if len(set(pairs)) == 1 else None
        if candidate is None:
            stats["ambiguous_category_counts"] += 1
        elif candidate == (evses["available"], evses["total"]):
            stats["pair_matches_unique_evses"] += 1
        else:
            stats["pair_differs_from_unique_evses"] += 1
            if len(examples) < 20:
                examples.append({
                    "key": list(k),
                    "category_pairs": [list(pair) for pair in pairs],
                    "unique_evses": evses,
                })
    return {"stats": dict(stats), "mismatches_sample": examples}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("geojson")
    p.add_argument("ocpi")
    p.add_argument("--out", help="Write result JSON to file")
    args = p.parse_args()
    report = compare(read_json(args.geojson), read_json(args.ocpi))
    payload = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        Path(args.out).write_text(payload + "\n", encoding="utf-8")
    print(payload)
    if report["stats"].get("matched", 0) == 0:
        raise SystemExit("No locations matched; inspect NDW identity fields before drawing conclusions.")


if __name__ == "__main__":
    main()
