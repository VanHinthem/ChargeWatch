#!/usr/bin/env python3
"""Isolated CI reproduction: a tariff disappears while its connector reference remains.
Default asserts safe behavior; --expect-known-bug explicitly characterizes current bug.
Never run against NAS: requires CI, loopback PostgreSQL, and a dedicated empty database.
"""
import argparse
import gzip
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import urlopen

DB = "chargewatch_tariff_presence_ci"
STAMP = "2026-10-28T12:00:00Z"
API = "http://127.0.0.1:5093/api/v1/"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sql(query):
    return subprocess.check_output(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", query],
        text=True, timeout=30).strip()


def tariff(key, price):
    return {"country_code": "NL", "party_id": "TST", "id": key,
            "currency": "EUR", "last_updated": STAMP,
            "elements": [{"price_components": [
                {"type": "ENERGY", "price": price, "step_size": 1, "vat": 21}]}]}


def get(path):
    with urlopen(API + path, timeout=15) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect-known-bug", action="store_true")
    args = parser.parse_args()
    require(os.environ.get("CI") == "true", "CI-only fixture")
    require(os.environ.get("PGHOST") in ("localhost", "127.0.0.1"), "Loopback DB required")
    require(os.environ.get("PGDATABASE") == DB, "Dedicated test DB required")
    require(sql("SELECT current_database()") == DB, "Wrong actual DB")
    require(sql("SELECT count(*) FROM charging_location") == "0", "Test DB must be empty")
    require(sql("SELECT count(*) FROM tariff") == "0", "Tariff test table must be empty")
    require(sql("SELECT version FROM schema_migration WHERE version='024_retire_legacy_duration_statistics'")
            == "024_retire_legacy_duration_statistics", "Latest schema required")

    location = {
        "country_code": "NL", "party_id": "TST", "id": "TARIFF-PRESENCE",
        "address": "CI tariff presence", "city": "Oss", "last_updated": STAMP,
        "coordinates": {"latitude": "51.75", "longitude": "5.53"},
        "evses": [{"uid": "E1", "status": "AVAILABLE", "last_updated": STAMP,
                   "connectors": [{"id": "C1", "standard": "IEC_62196_T2",
                                   "format": "SOCKET", "power_type": "AC_1_PHASE",
                                   "max_voltage": 230, "max_amperage": 16,
                                   "tariff_ids": ["T"], "last_updated": STAMP}]}]}
    state = {"phase": "A", "requests": []}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            phase = state["phase"]
            if self.path == "/locations":
                etag = '"locations-A"'
                if self.headers.get("If-None-Match") == etag:
                    self.send_response(304)
                    self.send_header("ETag", etag)
                    self.end_headers()
                    state["requests"].append([phase, self.path, 304])
                    return
                body = [location]
            elif self.path == "/tariffs":
                etag = '"tariffs-' + phase + '"'
                # B is nonempty: this is disappearance, not an empty-feed edge case.
                body = [tariff("KEEP", 0.30)]
                if phase == "A":
                    body.append(tariff("T", 0.42))
            else:
                self.send_error(404)
                return
            payload = gzip.compress(json.dumps(body).encode(), mtime=0)
            self.send_response(200)
            self.send_header("ETag", etag)
            self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            state["requests"].append([phase, self.path, 200])

        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    api_process = None
    try:
        with tempfile.TemporaryDirectory(prefix="cw-tariff-presence-") as cache:
            env = os.environ.copy()
            # These credentials belong exclusively to the disposable CI database.
            env["CW_IMPORT_CONNECTION"] = (
                "Host=127.0.0.1;Database=" + DB +
                ";Username=chargewatch_import;Password=import-test-only")
            env["CW_API_CONNECTION"] = (
                "Host=127.0.0.1;Database=" + DB +
                ";Username=chargewatch_read;Password=read-test-only")
            fixture = "http://127.0.0.1:" + str(server.server_port)
            env.update(CW_NDW_LOCATIONS_URL=fixture + "/locations",
                       CW_NDW_TARIFFS_URL=fixture + "/tariffs",
                       CW_NDW_CACHE_DIR=cache, CW_NDW_MIN_LOCATIONS="1",
                       CW_ENABLE_COMPACT_STATISTICS="1",
                       ASPNETCORE_URLS="http://127.0.0.1:5093")

            def poll():
                subprocess.run(
                    ["dotnet", "src/ChargeWatch.Importer/bin/Release/net10.0/ChargeWatch.Importer.dll",
                     "--watch-once"], env=env, check=True, timeout=120)

            poll()
            with open(Path(cache) / "api.log", "w+") as api_log:
                api_process = subprocess.Popen(
                    ["dotnet", "src/ChargeWatch.Api/bin/Release/net10.0/ChargeWatch.Api.dll"],
                    env=env, stdout=api_log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 30
                    while True:
                        require(api_process.poll() is None, "API exited before becoming ready")
                        try:
                            get("data-status")
                            break
                        except OSError:
                            if time.monotonic() >= deadline:
                                raise
                            time.sleep(0.2)
                    loc_id = sql("SELECT id FROM charging_location WHERE source_id='TARIFF-PRESENCE'")

                    def observe():
                        detail = get("locations/" + loc_id)
                        connector = detail["evses"][0]["connectors"][0]
                        require(len(connector["tariffOptions"]) == 1, "Reference T must remain visible")
                        option = connector["tariffOptions"][0]
                        require(option["sourceId"] == "T", "Unexpected tariff identity")
                        return {"tariffStatus": connector["tariffStatus"],
                                "components": option["components"],
                                "tariffsFeedIsStale": detail["tariffsFeedIsStale"],
                                "feedCheckedAt": detail["tariffsFeedLastCheckedAt"]}

                    before = observe()
                    require(before["tariffStatus"] == "APPLICABLE", "Baseline tariff must apply")
                    require(any(c["dimension"] == "ENERGY" and c["price"] == 0.42
                                for c in before["components"]), "Baseline ENERGY price missing")
                    state["phase"] = "B"
                    poll()
                    after = observe()
                    with gzip.open(Path(cache) / "tariffs.json.gz", "rt") as source:
                        source_ids = [t["id"] for t in json.load(source)]
                    require(source_ids == ["KEEP"], "B snapshot not promoted correctly")
                    require(["B", "/locations", 304] in state["requests"], "Expected Locations 304")
                    require(["B", "/tariffs", 200] in state["requests"], "Expected Tariffs 200")
                    require(after["tariffsFeedIsStale"] is False, "Fresh B feed must be visible")
                    require(after["feedCheckedAt"] > before["feedCheckedAt"], "Tariff checkpoint did not advance")
                    retained = sql("SELECT count(*) FROM tariff WHERE party_id='TST' AND source_id='T'")
                    evidence = {"before": before, "after": after,
                                "committedSourceTariffIds": source_ids,
                                "retainedTariffRows": int(retained), "requests": state["requests"]}
                    print(json.dumps(evidence, indent=2), flush=True)
                    bug = after["tariffStatus"] == "APPLICABLE" and any(
                        c["dimension"] == "ENERGY" and c["price"] == 0.42
                        for c in after["components"])
                    if args.expect_known_bug:
                        require(retained == "1" and bug, "Known bug no longer reproduced; update regression")
                        print("REPRODUCED KNOWN BUG: removed source tariff still APPLICABLE at 0.42; NOT a fix.")
                    else:
                        require(not bug and not after["components"],
                                "Removed source tariff must not expose the old current price")
                        print("PASS: removed tariff does not expose a current price.")
                finally:
                    if api_process.poll() is None:
                        api_process.terminate()
                        try:
                            api_process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            api_process.kill()
                            api_process.wait()
                    api_log.seek(0)
                    print(api_log.read())
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
