#!/usr/bin/env python3
"""Serve pre-generated national fixture without contacting NDW."""
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = ("/tmp/national-locations.json.gz" if self.path=="/locations" else
                "/tmp/national-tariffs.json.gz" if self.path=="/tariffs" else None)
        if path is None: self.send_error(404); return
        self.send_response(200)
        self.send_header("Content-Type","application/gzip")
        self.send_header("Content-Length",str(os.path.getsize(path)))
        self.send_header("ETag",'"national-v1"')
        self.end_headers()
        with open(path,"rb") as inp:
            while buf:=inp.read(128*1024): self.wfile.write(buf)
    def log_message(self,*args): pass
ThreadingHTTPServer(("127.0.0.1",18888),Handler).serve_forever()
