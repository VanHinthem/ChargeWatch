#!/usr/bin/env python3
"""Compare two actual NDW OCPI Locations gzip files; no database writes."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import hashlib

def capture(path):
    result = {}
    duplicates = 0
    with gzip.open(path, "rt", encoding="utf-8") as file:
        root = json.load(file)
    if not isinstance(root, list):
        raise ValueError("Expected an OCPI Locations JSON array")
    for loc in root:
        lk = (str(loc.get("country_code") or ""),str(loc.get("party_id") or ""),str(loc.get("id") or ""))
        for evse in loc.get("evses") or []:
            key = (*lk,str(evse.get("uid") or ""))
            if key in result:
                duplicates += 1
            result[key] = (str(evse.get("status") or "<missing>").upper(),
                           evse.get("last_updated"))
    if duplicates:
        raise ValueError(f"Duplicate EVSE keys: {duplicates}")
    return result

def run(a,b):
    before,after = capture(a),capture(b)
    shared = before.keys() & after.keys()
    counts = Counter()
    locations = defaultdict(Counter)
    changed_same_status_examples = []
    for key in shared:
        s0,t0=before[key]; s1,t1=after[key]
        sc=s0!=s1
        tc=t0!=t1
        category=("status_and_timestamp_changed" if sc and tc else
                  "status_only_changed" if sc else
                  "timestamp_only_changed" if tc else "unchanged")
        counts[category]+=1
        locations[key[:3]][category]+=1
        if tc and not sc and len(changed_same_status_examples)<5:
            changed_same_status_examples.append({"location":key[:3],"uid":key[3],
                                                  "before":t0,"after":t1})
    status_pairs=Counter((before[k][0],after[k][0]) for k in shared if before[k][0]!=after[k][0])
    aligned = sum(1 for items in locations.values() if len(items)>0 and
                  items["unchanged"]==sum(items.values()))
    sha_a=hashlib.sha256(Path(a).read_bytes()).hexdigest()
    sha_b=hashlib.sha256(Path(b).read_bytes()).hexdigest()
    return {
      "baseline":{"path":str(a),"sha256":sha_a,"evses":len(before)},
      "current":{"path":str(b),"sha256":sha_b,"evses":len(after)},
      "same_file_bytes":sha_a==sha_b,
      "shared_evses":len(shared),
      "only_baseline":len(before.keys()-after.keys()),
      "only_current":len(after.keys()-before.keys()),
      "changes":dict(sorted(counts.items())),
      "locations_with_shared_evses":len(locations),
      "locations_with_no_changes_in_shared_evses":aligned,
      "top_status_transitions":[{"from":x,"to":y,"count":v} for (x,y),v in status_pairs.most_common(12)],
      "same_status_new_timestamp_examples":changed_same_status_examples,
      "interpretation":"Timestamp-only changes are evidence of source refresh without a status-class change, not proof of continuous 5-minute observations. Two files alone do not prove all intermediate states."
    }

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline",required=True,type=Path,help="First real NDW Locations gzip")
    parser.add_argument("--current",required=True,type=Path,help="Later real NDW Locations gzip")
    parser.add_argument("--output",type=Path,default=Path("data/ndw-two-snapshot-report.json"))
    args=parser.parse_args()
    if args.baseline.resolve()==args.current.resolve():
        parser.error("Use two distinct files; never overwrite the baseline")
    report=run(args.baseline,args.current)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2,ensure_ascii=False))
    print(f"Saved {args.output}")
