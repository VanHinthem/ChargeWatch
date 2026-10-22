#!/usr/bin/env python3
"""One-off NDW OCPI discovery. Python 3.10+, standard library only.

Downloads two public gzip snapshots (or reuses --offline files), inventories fields
and statuses, finds Schietspoel 18, resolves tariffs, and writes small JSON results.
Does not retain credentials, post data, or change ChargeWatch production state.
"""
import argparse
import collections
import gzip
import json
import pathlib
import urllib.request
import datetime
import hashlib

URLS = {
    "locations": "https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz",
    "tariffs": "https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz",
}


def records(payload, label):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in (label, "data", "results", "items"):
            if isinstance(payload.get(key), list):
                return payload[key]
    raise ValueError(f"Unrecognized {label} JSON root; inspect report/source before changing parser")


def download(name, directory, offline):
    path = directory / (name + ".json.gz")
    meta = {}
    if not offline:
        request = urllib.request.Request(
            URLS[name], headers={"User-Agent": "ChargeWatch-NDW-Discovery/0.1"}
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            meta = {
                "url": response.geturl(),
                "etag": response.headers.get("ETag"),
                "last_modified": response.headers.get("Last-Modified"),
                "content_length": response.headers.get("Content-Length"),
                "downloaded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            }
            with path.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
    elif not path.is_file():
        raise FileNotFoundError(f"Missing --offline source: {path}")
    meta["size_bytes"] = path.stat().st_size
    meta["sha256"] = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    with gzip.open(path, "rt", encoding="utf-8") as f:
        payload = json.load(f)
    meta["root_type"] = type(payload).__name__
    meta["root_keys"] = sorted(payload) if isinstance(payload, dict) else []
    return records(payload, name), meta


def identity(obj):
    return (str(obj.get("country_code") or ""), str(obj.get("party_id") or ""), str(obj.get("id") or ""))


def run(args):
    directory = pathlib.Path(args.output)
    directory.mkdir(parents=True, exist_ok=True)
    locations, lm = download("locations", directory, args.offline)
    tariffs, tm = download("tariffs", directory, args.offline)
    statuses = collections.Counter()
    counts = collections.Counter()
    references = collections.Counter()
    matched = []
    location_fields, evse_fields, connector_fields = set(), set(), set()
    for loc in locations:
        if not isinstance(loc, dict):
            continue
        location_fields.update(loc)
        counts["locations"] += 1
        evses = loc.get("evses") or []
        for evse in evses:
            if not isinstance(evse, dict):
                continue
            evse_fields.update(evse)
            counts["evses"] += 1
            statuses[str(evse.get("status") or "<missing>")] += 1
            for connector in evse.get("connectors") or []:
                if not isinstance(connector, dict):
                    continue
                connector_fields.update(connector)
                counts["connectors"] += 1
                references.update(str(x) for x in connector.get("tariff_ids") or [])
        address = " ".join(str(loc.get(k) or "") for k in ("name", "address", "city"))
        if "schietspoel" in address.lower() and ("oss" in address.lower() or "18" in address):
            matched.append(loc)
    index = collections.defaultdict(list)
    tariff_fields = set()
    for tariff in tariffs:
        if not isinstance(tariff, dict):
            continue
        tariff_fields.update(tariff)
        index[identity(tariff)].append(tariff)
    resolved = []
    for loc in matched:
        for evse in loc.get("evses") or []:
            for connector in evse.get("connectors") or []:
                for tariff_id in connector.get("tariff_ids") or []:
                    key = (str(loc.get("country_code") or ""), str(loc.get("party_id") or ""), str(tariff_id))
                    resolved.append({"evse_uid": evse.get("uid"), "connector_id": connector.get("id"),
                                     "tariff_id": tariff_id, "matching_tariffs": index.get(key, [])})
    result = {
        "sources": {"locations": lm, "tariffs": tm},
        "counts": dict(counts), "tariffs": len(tariffs),
        "evse_statuses": dict(sorted(statuses.items())),
        "fields": {"location": sorted(location_fields), "evse": sorted(evse_fields),
                   "connector": sorted(connector_fields), "tariff": sorted(tariff_fields)},
        "schietspoel_matches": len(matched),
        "schietspoel_tariff_links": [
            {"evse_uid": x["evse_uid"], "connector_id": x["connector_id"],
             "tariff_id": x["tariff_id"], "matches": len(x["matching_tariffs"])} for x in resolved
        ],
        "distinct_referenced_tariffs": len(references),
    }
    (directory / "report.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    (directory / "schietspoel_locations.json").write_text(json.dumps(matched, indent=2, ensure_ascii=False), encoding="utf-8")
    (directory / "schietspoel_tariffs.json").write_text(
        json.dumps(resolved, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    print(f"Results stored in {directory}; keep raw gzip out of git.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="data/ndw-discovery")
    parser.add_argument("--offline", action="store_true", help="Analyze previously downloaded gzip files")
    run(parser.parse_args())
