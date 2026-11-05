#!/usr/bin/env python3
"""Verify actual HTTP gzip negotiation and JSON parity against the read-only API.

Run against the isolated CI database only, with either --feature-id (location,
viewport, batch) or --overview (feature-gated overview and paged cluster).
This test does not modify the API, collector, or database.
"""
import argparse
import gzip
import json
import urllib.parse
import urllib.request


def request(base, path, *, body=None, accept_gzip=False):
    headers = {"Accept": "application/json"}
    if accept_gzip:
        headers["Accept-Encoding"] = "gzip"
    if body is not None:
        headers["Content-Type"] = "application/json"
    call = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    with urllib.request.urlopen(call, timeout=20) as response:
        status = response.status
        encoding = response.headers.get("Content-Encoding")
        vary = response.headers.get("Vary", "")
        cache_control = response.headers.get("Cache-Control")
        raw = response.read()
    assert status == 200, (path, status)
    if accept_gzip:
        assert encoding == "gzip", (path, encoding)
        assert "accept-encoding" in vary.lower(), (path, vary)
        decoded = gzip.decompress(raw)
    else:
        assert encoding is None, (path, encoding)
        decoded = raw
    assert decoded, path
    return json.loads(decoded), raw, cache_control


def stable(value):
    """Ignore age fields calculated at response time, not actual data changes."""
    if isinstance(value, list):
        return [stable(item) for item in value]
    if isinstance(value, dict):
        return {
            key: stable(item) for key, item in value.items()
            if key not in ("sourceAgeSeconds", "feedAgeSeconds", "ageSeconds")
        }
    return value


def verify(base, name, path, *, body=None, no_store=False):
    plain, normal_bytes, original_cache = request(base, path, body=body)
    compressed, gzip_bytes, gzip_cache = request(
        base, path, body=body, accept_gzip=True
    )
    assert stable(compressed) == stable(plain), (name, "JSON parity")
    assert len(gzip_bytes) < len(normal_bytes), (
        name, "gzip must reduce size on meaningful JSON", len(normal_bytes),
        len(gzip_bytes),
    )
    if no_store:
        assert original_cache == gzip_cache == "no-store", (
            name, original_cache, gzip_cache,
        )
    print(
        f"{name}: {len(normal_bytes)} -> {len(gzip_bytes)} bytes "
        f"({100*(1-len(gzip_bytes)/len(normal_bytes)):.1f}% less), "
        "gzip negotiated, JSON identical"
    )
    return plain


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--feature-id")
    group.add_argument("--overview", action="store_true")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    if args.feature_id:
        # The fixture includes many EVSEs and connectors so payloads are
        # deliberately large enough for compression to be beneficial.
        verify(base, "locations",
               "/api/v1/locations?q=Oss&limit=20")
        verify(base, "viewport",
               "/api/v1/map/viewport?bbox=5.3,51.6,5.8,51.9")
        batch = {"featureIds": [args.feature_id], "includeStatistics": True}
        result = verify(base, "batch full detail and statistics",
                        "/api/v1/locations/by-ndw-id/batch", body=batch)
        assert len(result["locations"]) == 1
        assert result["locations"][0]["status"] == "FOUND"
        batch["includeStatistics"] = False
        verify(base, "batch without statistics",
               "/api/v1/locations/by-ndw-id/batch", body=batch)
    else:
        params = urllib.parse.urlencode({"bbox": "5.2,51.1,6.6,52.0", "zoom": "12"})
        overview = verify(base, "map overview", "/api/v1/map/overview?" + params,
                          no_store=True)
        cluster = next(
            (marker for marker in overview["markers"] if marker["kind"] == "cluster"),
            None,
        )
        assert cluster is not None, "Overview fixture must have clusters"
        params = urllib.parse.urlencode({
            "gridLevel": cluster["gridLevel"],
            "gridX": cluster["gridX"],
            "gridY": cluster["gridY"],
            "offset": 0, "limit": 30,
        })
        verify(base, "cluster cell page", "/api/v1/map/overview/cell?" + params,
               no_store=True)
    print("All gzip HTTP negotiation, JSON parity and map contracts passed.")


if __name__ == "__main__":
    main()
