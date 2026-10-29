#!/usr/bin/env python3
"""End-to-end tariff presence contract on a disposable PostgreSQL 18 database."""
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
KEEP_IDS = ["KEEP-" + str(n) for n in range(10)]


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
    require(os.environ.get("CI") == "true", "CI-only fixture")
    require(os.environ.get("PGHOST") in ("localhost", "127.0.0.1"), "Loopback DB required")
    require(os.environ.get("PGDATABASE") == DB, "Dedicated test DB required")
    require(sql("SELECT current_database()") == DB, "Wrong actual DB")
    require(sql("SELECT count(*) FROM charging_location") == "0", "Test DB must be empty")
    require(sql("SELECT count(*) FROM tariff") == "0", "Tariff table must be empty")
    require(sql("SELECT version FROM schema_migration WHERE version='025_tariff_snapshot_presence'")
            == "025_tariff_snapshot_presence", "Latest schema required")

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

    def tariffs_for(phase):
        if phase == "F":
            return []
        if phase == "E":
            return [tariff("KEEP-0", 0.30)]
        rows = [tariff(key, 0.30) for key in KEEP_IDS]
        if phase in ("A", "D"):
            rows.append(tariff("T", 0.42))
        return rows

    def tariff_version(phase):
        return "B" if phase == "C" else phase

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
                version = tariff_version(phase)
                etag = '"tariffs-' + version + '"'
                if self.headers.get("If-None-Match") == etag:
                    self.send_response(304)
                    self.send_header("ETag", etag)
                    self.end_headers()
                    state["requests"].append([phase, self.path, 304])
                    return
                body = tariffs_for(phase)
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
                       CW_NDW_MIN_TARIFFS="1", CW_ENABLE_COMPACT_STATISTICS="1",
                       ASPNETCORE_URLS="http://127.0.0.1:5093")

            def poll(expect_success=True):
                result = subprocess.run(
                    ["dotnet", "src/ChargeWatch.Importer/bin/Release/net10.0/ChargeWatch.Importer.dll",
                     "--watch-once"], env=env, text=True, capture_output=True, timeout=120)
                print(result.stdout, end="")
                print(result.stderr, end="")
                if expect_success:
                    require(result.returncode == 0, "Expected successful poll")
                else:
                    require(result.returncode != 0, "Unsafe tariff feed was accepted")

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
                                "limitations": option["limitations"],
                                "tariffsFeedIsStale": detail["tariffsFeedIsStale"],
                                "feedCheckedAt": detail["tariffsFeedLastCheckedAt"]}

                    before = observe()
                    require(before["tariffStatus"] == "APPLICABLE", "Baseline tariff must apply")
                    require(any(c["dimension"] == "ENERGY" and c["price"] == 0.42
                                for c in before["components"]), "Baseline ENERGY price missing")

                    state["phase"] = "B"
                    poll()
                    absent = observe()
                    require(absent["tariffStatus"] == "SOURCE_MISSING", absent)
                    require(absent["components"] == [], "Removed tariff exposed old components")
                    require("SOURCE_TARIFF_NOT_FOUND" in absent["limitations"], absent)
                    require(absent["tariffsFeedIsStale"] is False, "Fresh B feed must be visible")
                    require(absent["feedCheckedAt"] > before["feedCheckedAt"],
                            "Tariff feed checkpoint did not advance")
                    with gzip.open(Path(cache) / "tariffs.json.gz", "rt") as source:
                        source_ids = [t["id"] for t in json.load(source)]
                    require(source_ids == KEEP_IDS, "B snapshot not promoted correctly")
                    require(sql("SELECT is_active::text FROM tariff WHERE source_id='T'") == "false",
                            "Removed tariff row was not retained as inactive")
                    require(sql("SELECT tariff_count FROM ndw_tariffs_snapshot_checkpoint") == "10",
                            "Presence checkpoint count incorrect")
                    snapshot_at_b = sql(
                        "SELECT fetched_at::text FROM ndw_tariffs_snapshot_checkpoint WHERE feed_key='tariffs'")

                    state["phase"] = "C"
                    poll()
                    after_304 = observe()
                    require(after_304["tariffStatus"] == "SOURCE_MISSING", after_304)
                    require(sql("SELECT fetched_at::text FROM ndw_tariffs_snapshot_checkpoint WHERE feed_key='tariffs'")
                            == snapshot_at_b, "304 changed snapshot presence checkpoint")
                    require(["C", "/tariffs", 304] in state["requests"], "Tariffs 304 not exercised")

                    state["phase"] = "D"
                    poll()
                    returned = observe()
                    require(returned["tariffStatus"] == "APPLICABLE", returned)
                    require(any(c["dimension"] == "ENERGY" and c["price"] == 0.42
                                for c in returned["components"]), "Returned tariff price missing")
                    require(sql("SELECT is_active::text FROM tariff WHERE source_id='T'") == "true",
                            "Returned tariff did not reactivate")
                    checkpoint_d = sql(
                        "SELECT fetched_at::text||'|'||tariff_count FROM ndw_tariffs_snapshot_checkpoint")
                    feed_checked_d = returned["feedCheckedAt"]
                    cache_d = (Path(cache) / "tariffs.json.gz").read_bytes()

                    state["phase"] = "E"
                    poll(expect_success=False)
                    after_partial = observe()
                    require(after_partial["tariffStatus"] == "APPLICABLE", "Partial feed changed live tariff")
                    require(after_partial["feedCheckedAt"] == feed_checked_d,
                            "Failed partial feed advanced feed checkpoint")
                    require((Path(cache) / "tariffs.json.gz").read_bytes() == cache_d,
                            "Failed partial feed replaced cache")
                    require(sql("SELECT fetched_at::text||'|'||tariff_count FROM ndw_tariffs_snapshot_checkpoint")
                            == checkpoint_d, "Failed partial feed changed presence checkpoint")

                    state["phase"] = "F"
                    poll(expect_success=False)
                    after_empty = observe()
                    require(after_empty["tariffStatus"] == "APPLICABLE", "Empty feed changed live tariff")
                    require(after_empty["feedCheckedAt"] == feed_checked_d,
                            "Failed empty feed advanced feed checkpoint")
                    require((Path(cache) / "tariffs.json.gz").read_bytes() == cache_d,
                            "Failed empty feed replaced cache")
                    require(sql("SELECT fetched_at::text||'|'||tariff_count FROM ndw_tariffs_snapshot_checkpoint")
                            == checkpoint_d, "Failed empty feed changed presence checkpoint")

                    evidence = {
                        "baseline": before,
                        "afterDisappearance": absent,
                        "after304": after_304,
                        "afterReturn": returned,
                        "activeTariffs": int(sql("SELECT count(*) FROM tariff WHERE is_active")),
                        "retainedTariffs": int(sql("SELECT count(*) FROM tariff")),
                        "presenceCheckpoint": checkpoint_d,
                        "requests": state["requests"]}
                    print(json.dumps(evidence, indent=2), flush=True)
                    print("PASS: disappeared tariff is retained but cannot expose a current price.")
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
