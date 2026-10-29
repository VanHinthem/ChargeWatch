#!/usr/bin/env python3
"""Isolate structurally incomplete connector collections to their owning EVSE."""
import gzip
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB = "chargewatch_connector_completeness_ci"
STAMP = "2026-10-29T10:00:00Z"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sql(query):
    return subprocess.check_output(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", query],
        text=True, timeout=30).strip()


def connector(key):
    return {"id": key, "standard": "IEC_62196_T2", "format": "SOCKET",
            "power_type": "AC_3_PHASE", "max_voltage": 400,
            "max_amperage": 32, "last_updated": STAMP}


def tariff():
    return {"country_code": "NL", "party_id": "TST", "id": "RATE",
            "currency": "EUR", "last_updated": STAMP,
            "elements": [{"price_components": [
                {"type": "ENERGY", "price": 0.30, "step_size": 1}]}]}


def location(variant):
    evse = {"uid": "E1", "status": "AVAILABLE", "last_updated": STAMP,
            "connectors": [connector("C1"), connector("C2")]}
    if variant == "missing":
        del evse["connectors"]
    elif variant == "null":
        evse["connectors"] = None
    elif variant == "object":
        evse["connectors"] = {"id": "C1"}
    elif variant == "empty":
        evse["connectors"] = []
    other = {"uid": "E2", "status": "AVAILABLE", "last_updated": STAMP,
             "connectors": [connector("D1"), connector("D2")]
             if variant == "complete" else [connector("D1")]}
    return [{"country_code": "NL", "party_id": "TST", "id": "CONNECTOR-GUARD",
             "address": "CI connector guard", "city": "Oss",
             "last_updated": STAMP,
             "coordinates": {"latitude": "51.75", "longitude": "5.53"},
             "evses": [evse, other]}]


def main():
    require(os.environ.get("CI") == "true", "CI-only fixture")
    require(os.environ.get("PGHOST") in ("localhost", "127.0.0.1"),
            "Loopback DB required")
    require(os.environ.get("PGDATABASE") == DB, "Dedicated test DB required")
    require(sql("SELECT current_database()") == DB, "Wrong actual DB")
    require(sql("SELECT count(*) FROM charging_location") == "0", "Test DB must be empty")

    state = {"variant": "complete", "requests": []}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            variant = state["variant"]
            if self.path == "/locations":
                body = location(variant)
            elif self.path == "/tariffs":
                body = [tariff()]
            else:
                self.send_error(404)
                return
            payload = gzip.compress(json.dumps(body).encode(), mtime=0)
            self.send_response(200)
            self.send_header("ETag", '"' + self.path[1:] + "-" + variant + '"')
            self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            state["requests"].append([variant, self.path, 200])

        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="cw-connector-completeness-") as root:
            base_env = os.environ.copy()
            base_env["CW_IMPORT_CONNECTION"] = (
                "Host=127.0.0.1;Database=" + DB +
                ";Username=chargewatch_import;Password=import-test-only")
            fixture = "http://127.0.0.1:" + str(server.server_port)
            base_env.update(CW_NDW_LOCATIONS_URL=fixture + "/locations",
                            CW_NDW_TARIFFS_URL=fixture + "/tariffs",
                            CW_NDW_MIN_LOCATIONS="1", CW_NDW_MIN_TARIFFS="1")

            def poll(cache_name):
                cache = Path(root) / cache_name
                cache.mkdir()
                env = base_env.copy()
                env["CW_NDW_CACHE_DIR"] = str(cache)
                result = subprocess.run(
                    ["dotnet", "src/ChargeWatch.Importer/bin/Release/net10.0/ChargeWatch.Importer.dll",
                     "--watch-once"], env=env, text=True, capture_output=True, timeout=120)
                print(result.stdout, end="")
                print(result.stderr, end="")
                require(result.returncode == 0, cache_name + " poll failed")
                require((cache / "locations.json.gz").exists(),
                        cache_name + " Locations response was not promoted")
                require((cache / "tariffs.json.gz").exists(),
                        cache_name + " Tariffs response was not promoted")

            poll("baseline")
            require(sql("SELECT count(*) FROM connector WHERE is_active") == "4",
                    "Baseline connectors missing")
            baseline = sql("""
                SELECT source_sha256||'|'||fetched_at::text||'|'||location_count||'|'||evse_count
                FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'""")
            excluded_evse_state = sql("""
                SELECT string_agg(source_id||':'||is_active::text||':'||
                       consecutive_missing_snapshots::text,',' ORDER BY source_id)
                FROM connector c JOIN evse e ON e.id=c.evse_id
                WHERE e.source_uid='E1'""")

            for missing_count, variant in enumerate(
                    ("missing", "null", "object", "empty"), start=1):
                state["variant"] = variant
                poll(variant)
                current_checkpoint = sql("""
                    SELECT source_sha256||'|'||fetched_at::text||'|'||location_count||'|'||evse_count
                    FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'""")
                require(current_checkpoint != baseline,
                        variant + " did not publish the otherwise valid Locations snapshot")
                baseline = current_checkpoint
                require(sql("""
                    SELECT string_agg(source_id||':'||is_active::text||':'||
                           consecutive_missing_snapshots::text,',' ORDER BY source_id)
                    FROM connector c JOIN evse e ON e.id=c.evse_id
                    WHERE e.source_uid='E1'""") == excluded_evse_state,
                        variant + " changed presence for the excluded EVSE")
                require(sql("""
                    SELECT consecutive_missing_snapshots::text
                    FROM connector c JOIN evse e ON e.id=c.evse_id
                    WHERE e.source_uid='E2' AND c.source_id='D2'""") == str(missing_count),
                        variant + " prevented normal presence evidence for another EVSE")
                require(sql("""
                    SELECT is_active::text FROM connector c JOIN evse e ON e.id=c.evse_id
                    WHERE e.source_uid='E2' AND c.source_id='D1'""") == "true",
                        variant + " damaged the present connector on another EVSE")
                require([variant, "/locations", 200] in state["requests"],
                        variant + " did not exercise Locations HTTP 200")

            print(json.dumps({"excludedEvsePreserved": excluded_evse_state,
                              "isolatedVariants": ["missing", "null", "object", "empty"],
                              "otherEvseMissingEvidence": 4,
                              "requests": state["requests"]}, indent=2), flush=True)
            print("PASS: incomplete connector collections are isolated to their owning EVSE.")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
