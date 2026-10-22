#!/usr/bin/env python3
"""Build small genuine OCPI gzip files from the source-derived Sprint 0 fixture."""
import gzip
import json
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "tests/fixtures/ndw_schietspoel_2026-10-22.json"
output = Path(__file__).resolve().parents[1] / "data/sprint1-fixture"
output.mkdir(parents=True, exist_ok=True)
fixture = json.loads(source.read_text(encoding="utf-8"))
for name, rows in (("locations", [fixture["location"]]), ("tariffs", fixture["tariffs"])):
    with gzip.open(output / f"{name}.json.gz", "wt", encoding="utf-8") as stream:
        json.dump(rows, stream, ensure_ascii=False)
print(f"Written fixture gzip files to {output}")
