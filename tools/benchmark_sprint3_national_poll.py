#!/usr/bin/env python3
"""Opt-in national-scale importer benchmark. Writes ONLY chargewatch_statistics_test.
Two immutable gzip variants carry identical EVSE data but distinct SHA256 to exercise
poll checkpoint and coverage SQL. Never contacts NDW or touches benchmark DB.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

ROOT = Path(__file__).resolve().parents[1]
DB = "chargewatch_statistics_test"

def run(args, env=None):
    result = subprocess.run(args, cwd=ROOT, env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError("Failed: " + " ".join(map(str,args)) + "\n" + result.stdout)
    return result.stdout

def sql(statement):
    return run(["docker","compose","exec","-T","db","psql","-U","chargewatch_owner",
                "-d",DB,"-At","-v","ON_ERROR_STOP=1","-c",statement]).strip()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--locations", default="data/ndw-current/locations.json.gz")
    p.add_argument("--tariffs", default="data/sprint1-fixture/tariffs.json.gz")
    p.add_argument("--execute", action="store_true", help="Required: acknowledges imports into isolated test database")
    args = p.parse_args()
    conn = os.environ.get("CW_IMPORT_CONNECTION","")
    if not re.search(r"(?:^|;)\s*Database\s*=\s*chargewatch_statistics_test\s*(?:;|$)",conn,re.I):
        raise SystemExit("REFUSED: explicitly select Database=chargewatch_statistics_test.")
    if not args.execute:
        raise SystemExit("Dry-run: pass --execute to permit synthetic national imports into test DB.")
    loc = (ROOT / args.locations).resolve()
    tariff = (ROOT / args.tariffs).resolve()
    if not loc.is_file() or not tariff.is_file():
        raise SystemExit(f"Missing input: {loc} or {tariff}")
    original = loc.read_bytes()
    if original[:3] != b"\x1f\x8b\x08":
        raise SystemExit("Locations input is not gzip.")
    # Only change gzip MTIME header, not decompressed feed content.
    updated = bytearray(original)
    updated[4:8] = (int.from_bytes(updated[4:8],"little")+1).to_bytes(4,"little",signed=False)
    variants = [original,bytes(updated)]
    if hashlib.sha256(variants[0]).digest() == hashlib.sha256(variants[1]).digest():
        raise SystemExit("Distinct snapshot hashes required.")
    state = {"iteration":0}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/locations":
                payload = variants[state["iteration"]]
                tag = '"national-' + str(state["iteration"]) + '"'
            elif self.path == "/tariffs":
                payload = tariff.read_bytes()
                tag = '"tariff-national-test"'
            else:
                self.send_error(404); return
            if self.headers.get("If-None-Match") == tag:
                self.send_response(304); self.send_header("ETag",tag); self.end_headers(); return
            self.send_response(200)
            self.send_header("ETag",tag)
            self.send_header("Content-Type","application/gzip")
            self.send_header("Content-Length",str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        def log_message(self,*args): pass
    with tempfile.TemporaryDirectory(prefix="cw-national-scale-") as temp:
        server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        Thread(target=server.serve_forever,daemon=True).start()
        env=dict(os.environ)
        env.update(CW_NDW_CACHE_DIR=temp, CW_NDW_MIN_LOCATIONS="50000",
                   CW_NDW_LOCATIONS_URL=f"http://127.0.0.1:{server.server_port}/locations",
                   CW_NDW_TARIFFS_URL=f"http://127.0.0.1:{server.server_port}/tariffs",
                   CW_NDW_HTTP_TIMEOUT_SECONDS="600")
        command=["dotnet","run","--no-build","--project",
                 "src/ChargeWatch.Importer","--","--watch-once"]
        try:
            for phase in range(2):
                state["iteration"]=phase
                if phase==1:
                    first_sha=hashlib.sha256(variants[0]).hexdigest()
                    # Simulate a ~5-minute gap in test DB only; no wall clock delay.
                    sql("BEGIN; UPDATE ndw_snapshot_checkpoint SET fetched_at=fetched_at-interval '5 minutes' "
                        "WHERE source_name='ndw-locations' AND source_sha256='"+first_sha+"'; "
                        "UPDATE evse_poll_checkpoint SET observed_at=observed_at-interval '5 minutes' "
                        "WHERE snapshot_sha256='"+first_sha+"'; COMMIT;")
                start=time.monotonic()
                output=run(command,env)
                elapsed=time.monotonic()-start
                print(f"PASS national phase {phase+1}: {elapsed:.1f}s")
                print("\n".join(line for line in output.splitlines()
                      if "Staged:" in line or "Bulk committed:" in line))
            print("Candidate covered seconds:",sql(
                "SELECT coalesce(sum(covered_seconds),0) FROM evse_poll_hour_coverage"))
            print("Poll checkpoints:",sql("SELECT count(*) FROM evse_poll_checkpoint"))
        finally:
            server.shutdown()
            server.server_close()
if __name__=="__main__":
    main()
