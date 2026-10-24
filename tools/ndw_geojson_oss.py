#!/usr/bin/env python3
"""Inspect a small NDW GeoJSON area, without assuming a GeoJSON-to-OCPI ID mapping.

Python 3.10+, standard library only. No credentials or ChargeWatch API calls.
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ENDPOINT = "https://dotnl.ndw.nu/api/rest/geojson/dynamic-road-status/charge-point-data/v1/features"
DEFAULT_BBOX = (5.49, 51.74, 5.56, 51.80)  # small area in Oss
MAX_FEATURES = 1000


def bbox_from_text(value):
    try:
        coords = tuple(float(part) for part in value.split(","))
        if len(coords) != 4:
            raise ValueError("four values required")
        x1, y1, x2, y2 = coords
        if not (-180 <= x1 < x2 <= 180 and -90 <= y1 < y2 <= 90):
            raise ValueError("invalid bounds")
        if (x2 - x1) * (y2 - y1) > 1:
            raise ValueError("NDW bbox exceeds 1 square degree")
        return coords
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error


def inspect(payload, sample_size):
    if not isinstance(payload, dict) or payload.get("type") != "FeatureCollection":
        raise ValueError("Expected GeoJSON FeatureCollection")
    features = payload.get("features")
    if not isinstance(features, list):
        raise ValueError("Missing features array")
    if len(features) >= MAX_FEATURES:
        raise ValueError("Result at/above 1000 features; bbox may be incomplete; use a smaller bbox")

    sample = []
    feature_ids = []
    availability_shapes = Counter()
    all_props = Counter()
    invalid = 0
    for feature in features:
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            invalid += 1
            continue
        props = feature.get("properties")
        geometry = feature.get("geometry")
        if not isinstance(props, dict) or not isinstance(geometry, dict):
            invalid += 1
            continue
        all_props.update(props.keys())
        fid = feature.get("id")
        if fid is not None:
            feature_ids.append(str(fid))
        availabilities = props.get("availabilities")
        if isinstance(availabilities, list):
            for a in availabilities:
                if isinstance(a, dict):
                    availability_shapes.update(a.keys())
        if len(sample) < sample_size:
            # Include raw external IDs and observed fields; never manufacture a canonical OCPI key.
            sample.append({
                "feature_id": fid,
                "country": props.get("country"),
                "cpo_id": props.get("cpo_id"),
                "operator_name": props.get("operator_name"),
                "address": props.get("address"),
                "last_updated": props.get("last_updated"),
                "coordinates": geometry.get("coordinates"),
                "availabilities": availabilities,
                "properties_keys": sorted(props),
            })

    return {
        "type": payload.get("type"),
        "feature_count": len(features),
        "feature_properties_seen": sorted(all_props),
        "availability_properties_seen": sorted(availability_shapes),
        "missing_feature_id_count": len(features) - len(feature_ids),
        "duplicate_feature_id_count": len(feature_ids) - len(set(feature_ids)),
        "invalid_feature_count": invalid,
        "samples": sample,
        "identity_verified": False,
        "note": "A GeoJSON feature ID is NOT proven to be an OCPI location ID. Compare manually with charging_location (country_code,party_id,source_id).",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bbox", type=bbox_from_text, default=DEFAULT_BBOX, help="minLon,minLat,maxLon,maxLat")
    parser.add_argument("--input", type=Path, help="Analyze a locally saved GeoJSON file; no HTTP request")
    parser.add_argument("--output", type=Path, default=Path("data/ndw-geojson-oss/report.json"))
    parser.add_argument("--samples", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.samples <= 25:
        parser.error("--samples must be 1..25")

    if args.input:
        payload = json.loads(args.input.read_text(encoding="utf-8"))
    else:
        url = ENDPOINT + "?" + urlencode({"bbox": ",".join(str(x) for x in args.bbox)})
        request = Request(url, headers={"Accept": "application/geo+json, application/json", "User-Agent": "ChargeWatch-GeoJSON-Contract-Research/1"})
        try:
            with urlopen(request, timeout=20) as response:
                payload = json.load(response)
        except HTTPError as error:
            print(f"NDW HTTP {error.code}; Retry-After={error.headers.get('Retry-After')}; no retry", file=sys.stderr)
            return 1
        except (TimeoutError, URLError) as error:
            print(f"NDW unavailable: {error}; no retry", file=sys.stderr)
            return 1

    try:
        report = inspect(payload, args.samples)
    except ValueError as error:
        print(f"Contract validation failed: {error}", file=sys.stderr)
        return 2
    report["requested_bbox"] = args.bbox if not args.input else None
    report["source"] = "local-file" if args.input else ENDPOINT
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['feature_count']} features; {len(report['samples'])} samples; report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
