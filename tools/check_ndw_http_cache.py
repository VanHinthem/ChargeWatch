#!/usr/bin/env python3
"""Check NDW conditional HTTP behavior using public data, without downloading bodies."""
import urllib.request
import urllib.error

URLS = {
    "locations": "https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz",
    "tariffs": "https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz",
}

for name, url in URLS.items():
    print(f"\n{name}: {url}")
    try:
        with urllib.request.urlopen(urllib.request.Request(
            url, method="HEAD", headers={"User-Agent": "ChargeWatch-Discovery/0.1"}
        ), timeout=30) as response:
            etag = response.headers.get("ETag")
            modified = response.headers.get("Last-Modified")
            print(f"HEAD status={response.status}, ETag={etag}, Last-Modified={modified}")
        if not etag and not modified:
            print("No validators returned; conditional behavior untested.")
            continue
        headers = {"User-Agent": "ChargeWatch-Discovery/0.1"}
        if etag:
            headers["If-None-Match"] = etag
        else:
            headers["If-Modified-Since"] = modified
        try:
            with urllib.request.urlopen(urllib.request.Request(
                url, method="GET", headers=headers
            ), timeout=30) as response:
                print(f"Conditional GET status={response.status}")
                print("Warning: server returned body; request closed without downloading it.")
        except urllib.error.HTTPError as exc:
            print(f"Conditional GET status={exc.code}")
            if exc.code != 304:
                raise
    except (OSError, urllib.error.URLError) as exc:
        print(f"Check failed: {exc}")
