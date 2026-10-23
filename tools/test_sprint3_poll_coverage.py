#!/usr/bin/env python3
"""Isolated integration test: two distinct Locations snapshots and candidate coverage.
Shifts only TEST provenance timestamps backward to simulate five-minute cadence.
Never run against the benchmark/production database.
"""
import gzip
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
LOCATIONS = ROOT / "data/sprint1-fixture/locations.json.gz"
TARIFFS = ROOT / "data/sprint1-fixture/tariffs.json.gz"
DB = "chargewatch_statistics_test"

def command(args, env=None):
    p = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(f"Failed: {' '.join(args)}\n{p.stdout}\n{p.stderr}")
    return p.stdout

def sql(query):
    return command(["docker", "compose", "exec", "-T", "db", "psql", "-At",
                    "-v", "ON_ERROR_STOP=1", "-U", "chargewatch_owner",
                    "-d", DB, "-c", query]).strip()

def gzip_json(value):
    return gzip.compress(json.dumps(value, separators=(",", ":")).encode(), mtime=0)

def main():
    conn = os.getenv("CW_IMPORT_CONNECTION", "")
    if not re.search(r"(?:^|;)\s*Database\s*=\s*chargewatch_statistics_test\s*(?:;|$)", conn, re.I):
        raise SystemExit("REFUSED: CW_IMPORT_CONNECTION must target chargewatch_statistics_test.")
    source = json.loads(gzip.decompress(LOCATIONS.read_bytes()))
    nonce = uuid4().hex
    source[0]["address"] = "snapshot-smoke-" + nonce
    tariffs = TARIFFS.read_bytes()
    state = {"locations": gzip_json(source), "tariffs": tariffs,
             "loc_etag": '"snap-1-' + nonce + '"', "tar_etag": '"tariff-' + nonce + '"'}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            loc = self.path == "/locations"
            key = "locations" if loc else "tariffs"
            etag = state["loc_etag" if loc else "tar_etag"]
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304); self.send_header("ETag", etag); self.end_headers()
                return
            payload = state[key]
            self.send_response(200)
            self.send_header("ETag", etag)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        def log_message(self, *args):
            pass

    with tempfile.TemporaryDirectory(prefix="cw-poll-test-") as temp:
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        Thread(target=server.serve_forever, daemon=True).start()
        env = dict(os.environ)
        env.update(CW_NDW_CACHE_DIR=str(Path(temp) / "cache"), CW_NDW_MIN_LOCATIONS="1",
                   CW_NDW_LOCATIONS_URL=f"http://127.0.0.1:{server.server_port}/locations",
                   CW_NDW_TARIFFS_URL=f"http://127.0.0.1:{server.server_port}/tariffs")
        run = ["dotnet", "run", "--no-build", "--project", "src/ChargeWatch.Importer",
               "--", "--watch-once"]
        try:
            command(run, env)
            first_hash = hashlib.sha256(state["locations"]).hexdigest()
            cp = sql("SELECT source_sha256 FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'")
            assert cp == first_hash, "First snapshot was not committed"
            ids = sql("SELECT string_agg(evse_id::text,',') FROM evse_poll_checkpoint "
                      f"WHERE snapshot_sha256='{first_hash}'")
            assert ids, "EVSE checkpoints missing after first snapshot"
            print("PASS first snapshot: provenance + EVSE checkpoints")

            # Controlled simulation of 5-minute age, ONLY in isolated test DB.
            sql("BEGIN; "
                "UPDATE ndw_snapshot_checkpoint SET fetched_at=fetched_at-interval '5 minutes' "
                "WHERE source_name='ndw-locations' "
                f"AND source_sha256='{first_hash}'; "
                "UPDATE evse_poll_checkpoint SET observed_at=observed_at-interval '5 minutes' "
                f"WHERE snapshot_sha256='{first_hash}'; COMMIT;")
            previous = sql("SELECT coalesce(sum(covered_seconds),0) FROM evse_poll_hour_coverage "
                           f"WHERE evse_id IN ({ids})")
            source[0]["address"] += "-second"
            state["locations"] = gzip_json(source)
            state["loc_etag"] = '"snap-2-' + nonce + '"'
            command(run, env)
            after = sql("SELECT coalesce(sum(covered_seconds),0) FROM evse_poll_hour_coverage "
                        f"WHERE evse_id IN ({ids})")
            delta = int(after)-int(previous)
            assert 0 < delta <= 1800, f"Unexpected coverage delta: {delta} seconds"
            print(f"PASS second unchanged-status snapshot: +{delta} candidate EVSE-seconds")

            command(run, env)  # HTTP 304 replay
            replay = sql("SELECT coalesce(sum(covered_seconds),0) FROM evse_poll_hour_coverage "
                         f"WHERE evse_id IN ({ids})")
            assert int(replay) == int(after), "304 replay duplicated candidate coverage"
            print("PASS HTTP304 replay: no extra seconds")

            def age_checkpoint(minutes):
                current_hash = hashlib.sha256(state["locations"]).hexdigest()
                sql("BEGIN; "
                    "UPDATE ndw_snapshot_checkpoint SET fetched_at=fetched_at-interval '" + str(minutes) + " minutes' "
                    "WHERE source_name='ndw-locations' "
                    "AND source_sha256='" + current_hash + "'; "
                    "UPDATE evse_poll_checkpoint SET observed_at=observed_at-interval '" + str(minutes) + " minutes' "
                    "WHERE snapshot_sha256='" + current_hash + "'; COMMIT;")

            def coverage():
                return int(sql("SELECT coalesce(sum(covered_seconds),0) "
                               "FROM evse_poll_hour_coverage WHERE evse_id IN (" + ids + ")"))

            def publish(label):
                source[0]["address"] += "-" + label
                state["locations"] = gzip_json(source)
                state["loc_etag"] = '"snap-' + label + '-' + nonce + '"'
                command(run, env)

            # A gap longer than 15 min must not be extrapolated.
            age_checkpoint(20)
            baseline = coverage()
            publish("gap")
            assert coverage() == baseline, "Long gap fabricated candidate coverage"
            print("PASS >15-minute gap: no extra seconds")

            # Switch every EVSE to an unlike class; do not assign a transition time.
            age_checkpoint(5)
            baseline = coverage()
            for evse in source[0]["evses"]:
                old = evse["status"]
                evse["status"] = "CHARGING" if old == "AVAILABLE" else "AVAILABLE"
            publish("changed-status")
            assert coverage() == baseline, "Status transition fabricated coverage"
            print("PASS changed EVSE states: no extra seconds")

            # Unchanged class but a regressing last_updated is not a valid interval.
            age_checkpoint(5)
            baseline = coverage()
            for evse in source[0]["evses"]:
                evse["last_updated"] = "2000-01-01T00:00:00Z"
            publish("backward-source-time")
            assert coverage() == baseline, "Backward source time fabricated coverage"
            print("PASS backward source timestamps: no extra seconds")

            # One EVSE disappears: it must not accumulate time while absent.
            # Keep at least one EVSE to preserve a valid Locations fixture.
            age_checkpoint(5)
            baseline = coverage()
            removed = source[0]["evses"].pop()
            removed_uid = removed["uid"]
            missing_id = sql("SELECT e.id FROM evse e JOIN charging_location l ON l.id=e.location_id "
                             "WHERE e.source_uid='" + removed_uid.replace("'", "''") + "' "
                             "ORDER BY e.id DESC LIMIT 1")
            assert missing_id, "Missing target EVSE for disappearance check"
            before_missing = int(sql("SELECT coalesce(sum(covered_seconds),0) "
                                     "FROM evse_poll_hour_coverage WHERE evse_id=" + missing_id))
            publish("missing-evse")
            after_missing = int(sql("SELECT coalesce(sum(covered_seconds),0) "
                                    "FROM evse_poll_hour_coverage WHERE evse_id=" + missing_id))
            assert after_missing == before_missing, "Missing EVSE gained coverage"
            print("PASS missing EVSE: no coverage while absent")

            # Reappearance after a missing snapshot must not bridge that gap.
            age_checkpoint(5)
            source[0]["evses"].append(removed)
            publish("reappeared-evse")
            after_reappear = int(sql("SELECT coalesce(sum(covered_seconds),0) "
                                     "FROM evse_poll_hour_coverage WHERE evse_id=" + missing_id))
            assert after_reappear == before_missing, "Reappearing EVSE bridged missing snapshot"
            print("PASS reappeared EVSE: no bridged coverage")

            # Both endpoints must have reasonably fresh NDW source timestamps.
            # The older fixture timestamps (year 2000) must never earn coverage,
            # even when status and source timestamps remain unchanged.
            age_checkpoint(5)
            stale_baseline = coverage()
            publish("stale-unchanged")
            assert coverage() == stale_baseline, "Stale unchanged status fabricated coverage"
            print("PASS stale unchanged source timestamps: no coverage")



        finally:
            server.shutdown(); server.server_close()

if __name__ == "__main__":
    main()
