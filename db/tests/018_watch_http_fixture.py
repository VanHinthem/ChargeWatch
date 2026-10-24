#!/usr/bin/env python3
"""Local-only deterministic HTTP feed for full WatchRunner integration tests."""
import gzip
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

locations=[{"country_code":"NL","party_id":"TST","id":"LOC001","address":"CI test",
 "city":"Test","evses":[{"uid":"EVSE001","status":"AVAILABLE","last_updated":"2026-10-24T08:00:00Z","connectors":[{"id":"1","standard":"IEC_62196_T2","power_type":"AC_3_PHASE"}]}]}]
sources={"/locations":gzip.compress(json.dumps(locations).encode()),"/tariffs":gzip.compress(b"[]")}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in sources:
            self.send_error(404); return
        newest = Path("/tmp/cw-fixture-postcommit").exists() and self.path == "/locations"
        changed = (newest or Path("/tmp/cw-fixture-changed").exists()) and self.path == "/locations"
        etag = '"fixtures-v3"' if newest else ('"fixtures-v2"' if changed else '"fixtures-v1"')
        data = sources[self.path]
        if changed:
            new_locations = json.loads(json.dumps(locations))
            new_locations[0]["evses"][0]["status"] = "INOPERATIVE" if newest else "CHARGING"
            data = gzip.compress(json.dumps(new_locations).encode())
        if self.headers.get("If-None-Match")==etag:
            self.send_response(304); self.end_headers(); return
        self.send_response(200)
        self.send_header("Content-Type","application/gzip")
        self.send_header("Content-Length",str(len(data)))
        self.send_header("ETag",etag)
        self.end_headers()
        self.wfile.write(data)
    def log_message(self,*args): pass

if __name__=="__main__":
    ThreadingHTTPServer(("127.0.0.1",int(os.environ.get("TEST_HTTP_PORT","18887"))), Handler).serve_forever()
