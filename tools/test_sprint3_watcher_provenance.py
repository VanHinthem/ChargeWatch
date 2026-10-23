#!/usr/bin/env python3
"""Integration-check NDW watcher HTTP200/304/tariffs-only against isolated test DB."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

ROOT = Path(__file__).resolve().parents[1]
LOCATIONS = ROOT / "data/sprint1-fixture/locations.json.gz"
TARIFFS = ROOT / "data/sprint1-fixture/tariffs.json.gz"
DB = "chargewatch_statistics_test"

def call(args, env=None):
    run = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if run.returncode:
        raise RuntimeError(f"Command failed ({run.returncode}): {' '.join(str(x) for x in args)}\n{run.stdout}\n{run.stderr}")
    return run.stdout

def database_snapshot():
    query = ("SELECT coalesce((SELECT source_sha256 FROM ndw_snapshot_checkpoint "
             "WHERE source_name='ndw-locations'),'<none>') || '|' || "
             "coalesce((SELECT fetched_at::text FROM ndw_snapshot_checkpoint "
             "WHERE source_name='ndw-locations'),'<none>') || '|' || "
             "(SELECT count(*) FROM import_run WHERE status='completed')::text;")
    return call(["docker", "compose", "exec", "-T", "db", "psql",
                 "-U", "chargewatch_owner", "-d", DB, "-At", "-c", query]).strip()

def main():
    connection = os.getenv("CW_IMPORT_CONNECTION", "")
    if not re.search(r"(?:^|;)\s*Database\s*=\s*chargewatch_statistics_test\s*(?:;|$)", connection, re.I):
        raise SystemExit("Refusing test: CW_IMPORT_CONNECTION must explicitly select chargewatch_statistics_test.")
    for f in (LOCATIONS, TARIFFS):
        if not f.exists():
            raise SystemExit(f"Missing fixture: {f}; run python tools/build_sprint1_fixture.py")
    with tempfile.TemporaryDirectory(prefix="cw-watch-test-") as temporary:
        cache = Path(temporary) / "cache"
        cache.mkdir()
        # Never rely on a public feed or mutate checked-in fixtures.
        tariff_rows = json.loads(gzip.decompress(TARIFFS.read_bytes()))
        tariff_rows[0]["last_updated"] = "2026-10-23T10:57:00Z"
        second_tariffs = gzip.compress(json.dumps(tariff_rows).encode("utf-8"), mtime=0)
        fixture_locations = LOCATIONS.read_bytes()
        fixture_tariffs = TARIFFS.read_bytes()
        state = {"locations": fixture_locations, "tariffs": fixture_tariffs,
                 "location_etag": '"locations-v1"', "tariff_etag": '"tariffs-v1"',
                 "requests": []}

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                kind = self.path.strip("/")
                if kind not in ("locations", "tariffs"):
                    self.send_error(404)
                    return
                etag = state["location_etag" if kind == "locations" else "tariff_etag"]
                match = self.headers.get("If-None-Match")
                state["requests"].append((kind, match))
                if match == etag:
                    self.send_response(304)
                    self.send_header("ETag", etag)
                    self.end_headers()
                    return
                content = state[kind]
                self.send_response(200)
                self.send_header("ETag", etag)
                self.send_header("Content-Type", "application/gzip")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        env = dict(os.environ)
        env.update(CW_NDW_CACHE_DIR=str(cache), CW_NDW_MIN_LOCATIONS="1",
                   CW_NDW_LOCATIONS_URL=f"http://127.0.0.1:{server.server_port}/locations",
                   CW_NDW_TARIFFS_URL=f"http://127.0.0.1:{server.server_port}/tariffs")
        try:
            before = database_snapshot()
            print("Baseline checkpoint/imports:", before)
            first = call(["dotnet", "run", "--no-build", "--project",
                          "src/ChargeWatch.Importer", "--", "--watch-once"], env)
            after_200 = database_snapshot()
            expected_hash = hashlib.sha256(fixture_locations).hexdigest()
            if not after_200.startswith(expected_hash + "|"):
                raise AssertionError("HTTP200 did not write Locations SHA into checkpoint.")
            if after_200 == before:
                raise AssertionError("HTTP200 did not advance provenance.")
            print("PASS HTTP200 Locations: new SHA/fetch timestamp committed")
            second = call(["dotnet", "run", "--no-build", "--project",
                           "src/ChargeWatch.Importer", "--", "--watch-once"], env)
            after_304 = database_snapshot()
            if after_304 != after_200:
                raise AssertionError("304 unexpectedly changed checkpoint or import count.")
            if "304" not in second:
                raise AssertionError("Conditional GET did not produce HTTP304.")
            print("PASS HTTP304: no new import and unchanged provenance")
            state["tariffs"] = second_tariffs
            state["tariff_etag"] = '"tariffs-v2"'
            third = call(["dotnet", "run", "--no-build", "--project",
                          "src/ChargeWatch.Importer", "--", "--watch-once"], env)
            after_tariff = database_snapshot()
            if after_tariff.split("|")[:2] != after_200.split("|")[:2]:
                raise AssertionError("Tariffs-only update changed Locations provenance.")
            if int(after_tariff.split("|")[2]) != int(after_200.split("|")[2]) + 1:
                raise AssertionError("Tariffs-only update did not commit exactly one import.")
            if "304" not in third or "200" not in third:
                raise AssertionError("Expected Locations 304 and Tariffs 200.")
            print("PASS tariffs-only HTTP200: one import, Locations provenance unchanged")
            print("All three scenarios passed. Test cache was temporary.")
        finally:
            server.shutdown()
            server.server_close()

if __name__ == "__main__":
    main()
