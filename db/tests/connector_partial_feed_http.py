#!/usr/bin/env python3
"""Reject structurally incomplete connector collections before reconciliation."""
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
    return [{"country_code": "NL", "party_id": "TST", "id": "CONNECTOR-GUARD",
             "address": "CI connector guard", "city": "Oss",
             "last_updated": STAMP,
             "coordinates": {"latitude": "51.75", "longitude": "5.53"},
             "evses": [evse]}]


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
                            CW_NDW_MIN_LOCATIONS="1", CW_NDW_MIN_TARIFFS="1",
                            CW_ENABLE_COMPACT_STATISTICS="1")

            def poll(cache_name, expect_success):
                cache = Path(root) / cache_name
                cache.mkdir()
                env = base_env.copy()
                env["CW_NDW_CACHE_DIR"] = str(cache)
                result = subprocess.run(
                    ["dotnet", "src/ChargeWatch.Importer/bin/Release/net10.0/ChargeWatch.Importer.dll",
                     "--watch-once"], env=env, text=True, capture_output=True, timeout=120)
                print(result.stdout, end="")
                print(result.stderr, end="")
                if expect_success:
                    require(result.returncode == 0, "Complete baseline poll failed")
                else:
                    require(result.returncode != 0, "Incomplete connector collection was accepted")
                    require(not (cache / "locations.json.gz").exists(),
                            "Rejected Locations response was promoted to cache")
                    require(not (cache / "tariffs.json.gz").exists(),
                            "Rejected transaction promoted Tariffs cache")

            poll("baseline", True)
            require(sql("SELECT count(*) FROM connector WHERE is_active") == "2",
                    "Baseline connectors missing")
            baseline = sql("""
                SELECT source_sha256||'|'||fetched_at::text||'|'||location_count||'|'||evse_count
                FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'""")
            connector_state = sql("""
                SELECT string_agg(source_id||':'||is_active::text||':'||
                       consecutive_missing_snapshots::text,',' ORDER BY source_id)
                FROM connector""")
            feed_checkpoint = sql("""
                SELECT last_successful_check_at::text||'|'||last_changed_at::text
                FROM ndw_tariffs_feed_checkpoint WHERE feed_key='tariffs'""")

            for variant in ("missing", "null", "object", "empty"):
                state["variant"] = variant
                poll(variant, False)
                require(sql("""
                    SELECT source_sha256||'|'||fetched_at::text||'|'||location_count||'|'||evse_count
                    FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'""") == baseline,
                        variant + " changed Locations checkpoint")
                require(sql("""
                    SELECT string_agg(source_id||':'||is_active::text||':'||
                           consecutive_missing_snapshots::text,',' ORDER BY source_id)
                    FROM connector""") == connector_state,
                        variant + " changed connector presence")
                require(sql("""
                    SELECT last_successful_check_at::text||'|'||last_changed_at::text
                    FROM ndw_tariffs_feed_checkpoint WHERE feed_key='tariffs'""") == feed_checkpoint,
                        variant + " advanced Tariffs feed checkpoint")
                require([variant, "/locations", 200] in state["requests"],
                        variant + " did not exercise Locations HTTP 200")

            print(json.dumps({"baseline": connector_state,
                              "rejectedVariants": ["missing", "null", "object", "empty"],
                              "requests": state["requests"]}, indent=2), flush=True)
            print("PASS: incomplete connector collections roll back before presence reconciliation.")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
