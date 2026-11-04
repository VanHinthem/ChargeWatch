#!/usr/bin/env python3
"""True HTTP/Npgsql/PostGIS contract check for ChargeWatch #113 phase C.

CI starts the API under chargewatch_read. Independent expected markers are
derived from owner-read fixture rows, not the overview SQL query.
"""
import json
import math
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

BASE = os.environ.get("CW_OVERVIEW_TEST_URL", "http://127.0.0.1:5091/api/v1/")
HALF = 20037508.342789244
WORLD = 40075016.68557849
RADIUS = 6378137.0
BROAD = "5.2,51.1,6.6,52.0"
NEAR = "5.300,51.579,5.320,51.581"
DETAIL = "5.50,51.70,5.62,51.80"

def query(sql):
    out = subprocess.check_output(
        ["psql", "-X", "-A", "-t", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        text=True,
    )
    return [row.split("\t") for row in out.splitlines() if row]

def fetch(params=None, expected_status=200):
    url = BASE + "map/overview"
    if params is not None:
        url += "?" + urllib.parse.urlencode(params, doseq=True)
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            status = response.status
            result = json.load(response)
            headers = response.headers
    except urllib.error.HTTPError as failure:
        status = failure.code
        result = json.load(failure)
        headers = failure.headers
    assert status == expected_status, (url, status, result)
    return result, headers

locations = {}
for row in query("""SELECT id,country_code,party_id,source_id,longitude,latitude,
                        coalesce(operator_name,'')
                    FROM charging_location WHERE party_id='OVR'"""):
    id_, country, party, source, lon, lat, operator = row
    locations[int(id_)] = dict(country=country, party=party, source=source,
                               lon=float(lon), lat=float(lat), operator=operator,
                               evses={})
for id_, evse_id, status, active in query("""SELECT e.location_id,e.id,e.status,e.is_active
    FROM evse e JOIN charging_location l ON l.id=e.location_id
    WHERE l.party_id='OVR'"""):
    locations[int(id_)]["evses"][int(evse_id)] = dict(status=status, active=active=="t",
                                                       connectors=[])
for evse_id, standard, mode, watts, active in query("""SELECT c.evse_id,
    coalesce(c.standard,''),coalesce(c.power_type,''),
    coalesce(c.max_electric_power::text,''),c.is_active
    FROM connector c JOIN evse e ON e.id=c.evse_id
    JOIN charging_location l ON l.id=e.location_id WHERE l.party_id='OVR'"""):
    # The fixture has no duplicate connector IDs in this projection.
    for loc in locations.values():
        if int(evse_id) in loc["evses"]:
            loc["evses"][int(evse_id)]["connectors"].append(
                dict(standard=standard,mode=mode,watts=int(watts or 0),active=active=="t"))
            break

def counts(loc):
    active = [e for e in loc["evses"].values() if e["active"]]
    free = sum(e["status"].upper() == "AVAILABLE" for e in active)
    total = sum(e["status"].strip().upper() not in ("REMOVED","PLANNED") for e in active)
    assert 0 <= free <= total
    return free,total

def matches(loc, params):
    if "operator" in params:
        requested = params["operator"]
        if isinstance(requested, str):
            requested = [requested]
        if loc["operator"].strip() not in requested:
            return False
    technical = any(x in params for x in ("connectorType","mode","power"))
    def match_evse(e):
        if not e["active"] or e["status"].strip().upper() in ("REMOVED","PLANNED"):
            return False
        if not technical:
            return True
        def match_connector(c):
            if not c["active"]:
                return False
            standard = {"IEC_62196_T2":"type2","IEC_62196_T2_COMBO":"ccs2",
                        "CHADEMO":"chademo"}.get(c["standard"].upper(),"other")
            mode = "dc" if c["mode"].upper()=="DC" else (
                "ac" if c["mode"].upper() in ("AC_1_PHASE","AC_3_PHASE") else "other")
            if "connectorType" in params and standard not in params["connectorType"]:
                return False
            if "mode" in params and mode not in params["mode"]:
                return False
            power = params.get("power")
            if power is not None and not (
                (power=="lt50" and 0 < c["watts"] < 50000) or
                (power=="gte50" and c["watts"] >= 50000) or
                (power=="gte150" and c["watts"] >= 150000) or
                (power=="gte300" and c["watts"] >= 300000)):
                return False
            return True
        return any(match_connector(c) for c in e["connectors"])
    if technical and not any(match_evse(e) for e in loc["evses"].values()):
        return False
    min_free = int(params.get("minFree",0))
    if min_free and sum(e["status"].strip().upper()=="AVAILABLE" and match_evse(e)
                        for e in loc["evses"].values()) < min_free:
        return False
    return True

def cell_at(loc, level):
    size = WORLD / (1 << level)
    x = RADIUS * math.radians(loc["lon"])
    y = RADIUS * math.log(math.tan(math.pi/4 + math.radians(loc["lat"])/2))
    limit = (1 << level) - 1
    return (min(limit,max(0,math.floor((x+HALF)/size))),
            min(limit,max(0,math.floor((HALF-y)/size))))

def verify(params, *, expected_total=None, kinds=None):
    response, headers = fetch(params)
    assert response["schemaVersion"]==1 and response["filterVersion"]==1, response
    assert response["coverage"]["complete"] is True, response
    assert response["coverage"]["gridLevel"] == response["gridLevel"]
    assert response["dataVersion"] is None
    assert headers["X-ChargeWatch-Filter-Version"]=="1"
    assert headers["Cache-Control"]=="no-store"
    level = response["gridLevel"]
    assert 0 <= level <= min(22,math.floor(float(params["zoom"]))+2)
    c = response["coverage"]
    markers = response["markers"]
    assert len(markers) <= 500
    assert (c["maxCellX"]-c["minCellX"]+1)*(c["maxCellY"]-c["minCellY"]+1) <= 500
    assert len({(m["gridLevel"],m["gridX"],m["gridY"]) for m in markers}) == len(markers)

    expected = defaultdict(list)
    for loc in locations.values():
        if not matches(loc,params):
            continue
        x,y = cell_at(loc,level)
        if c["minCellX"] <= x <= c["maxCellX"] and c["minCellY"] <= y <= c["maxCellY"]:
            expected[x,y].append(loc)
    returned_cells = {(m["gridX"],m["gridY"]) for m in markers}
    assert returned_cells == set(expected), (returned_cells ^ set(expected),params)
    population = 0
    for marker in markers:
        assert marker["gridLevel"] == level
        members = expected[marker["gridX"],marker["gridY"]]
        population += len(members)
        if len(members)==1:
            loc = members[0]
            free,total = counts(loc)
            assert marker["kind"]=="location", marker
            assert marker["featureId"]=="-".join((loc["country"],loc["party"],loc["source"]))
            assert (marker["availableCount"],marker["totalCount"]) == (free,total), marker
            assert marker["countQuality"]==("known" if total else "unknown")
            assert abs(marker["longitude"]-loc["lon"])<1e-7
            assert abs(marker["latitude"]-loc["lat"])<1e-7
        else:
            green = red = unknown = 0
            for loc in members:
                free,total = counts(loc)
                green += int(free>0)
                red += int(free==0 and total>0)
                unknown += int(total==0)
            assert marker["kind"]=="cluster", marker
            assert (marker["locationCount"],marker["locationsWithFree"],
                    marker["locationsWithNoFree"],marker["locationsUnknown"]) == (
                len(members),green,red,unknown),marker
            assert green+red+unknown==len(members)
            size = WORLD/(1<<level)
            cx,cy=marker["gridX"],marker["gridY"]
            expected_lon=((cx+0.5)*size-HALF)/RADIUS*180/math.pi
            expected_lat=(2*math.atan(math.exp((HALF-(cy+0.5)*size)/RADIUS))
                          -math.pi/2)*180/math.pi
            assert abs(marker["longitude"]-expected_lon)<1e-7
            assert abs(marker["latitude"]-expected_lat)<1e-7
    assert response["representedLocationCount"] == population, response
    if expected_total is not None:
        assert population == expected_total, (population, expected_total, params)
    if kinds:
        assert set(kinds).issubset({m["kind"] for m in markers}), (kinds,params,markers)
    print(f"PASS zoom={params['zoom']}, level={level}, markers={len(markers)}, locations={population}, filters={len(params)-2}")
    return response

assert len(locations)==1635, len(locations)
base = dict(bbox=BROAD,zoom="12")
verify(base,expected_total=1635)
for z in ["4","7","17","22"]:
    verify(dict(base,zoom=z),expected_total=1635)
verify(dict(bbox=NEAR,zoom="18"),expected_total=3,kinds={"location"})
verify(dict(bbox=DETAIL,zoom="14"),kinds={"cluster","location"})
verify(dict(bbox="2.0,49.0,2.1,49.1",zoom="17"),expected_total=0)
verify(dict(base,operator="Fastned"))
verify(dict(base,connectorType=["ccs2"],mode=["dc"]))
verify(dict(base,connectorType=["ccs2"],mode=["dc"],power="gte150"))
verify(dict(base,power="lt50"))
verify(dict(base,minFree="1"))
verify(dict(base,connectorType=["ccs2"],mode=["dc"],power="gte150",
            minFree="1",operator="Fastned"))
verify(dict(base,minFree="2"),expected_total=0)
# Exact grid crossing and four neighboring quadrants: unique, complete ownership.
corner = next(loc for loc in locations.values() if loc["source"]=="EDGE-CORNER")
edge_bbox = (f"{corner['lon']-.025},{corner['lat']-.025},"
             f"{corner['lon']+.025},{corner['lat']+.025}")
edge_response = verify(dict(bbox=edge_bbox,zoom="12"))
assert edge_response["representedLocationCount"] >= 5
# Repeated tiny pan at same zoom must not change selected grid resolution.
b = verify(dict(bbox="5.2000,51.1000,6.6000,52.0000",zoom="12"))
p = verify(dict(bbox="5.20001,51.10001,6.60001,52.00001",zoom="12"))
assert b["gridLevel"]==p["gridLevel"],"tiny pan unexpectedly changed grid resolution"
# Adjacent/overlapping requests must retain canonical cell ownership and counts.
verify(dict(bbox="5.3,51.4,5.9,51.9",zoom="14"))
verify(dict(bbox="5.6,51.4,6.2,51.9",zoom="14"))
for bad in [
    dict(bbox=BROAD),dict(bbox=BROAD,zoom="NaN"),dict(bbox=BROAD,zoom="Infinity"),
    dict(bbox=BROAD,zoom="23"),dict(bbox="5,52,4,51",zoom="10"),
    dict(bbox="5,86,6,87",zoom="10"),dict(bbox=BROAD,zoom="12",power="invalid"),
]:
    fetch(bad,expected_status=400)

def verify_cell_page(cluster, offset, limit, filters=None, expected_status=200):
    params = dict(gridLevel=cluster["gridLevel"],gridX=cluster["gridX"],
                  gridY=cluster["gridY"],offset=offset,limit=limit)
    params.update(filters or {})
    url = BASE + "map/overview/cell?" + urllib.parse.urlencode(params, doseq=True)
    try:
        with urllib.request.urlopen(url,timeout=20) as response:
            status=response.status
            payload=json.load(response)
            headers=response.headers
    except urllib.error.HTTPError as failure:
        status=failure.code
        payload=json.load(failure)
        headers=failure.headers
    assert status==expected_status,(url,status,payload)
    if status!=200:
        return
    assert headers["Cache-Control"]=="no-store"
    assert headers["X-ChargeWatch-Filter-Version"]=="1"
    assert (payload["gridLevel"],payload["gridX"],payload["gridY"]) == (
        cluster["gridLevel"],cluster["gridX"],cluster["gridY"])
    expected = sorted([
        (id_,loc) for id_,loc in locations.items()
        if cell_at(loc,cluster["gridLevel"]) == (cluster["gridX"],cluster["gridY"]) and
        matches(loc,filters or {})
    ])
    assert payload["totalCount"]==len(expected),(payload["totalCount"],len(expected))
    expected_page=expected[offset:offset+limit]
    assert len(payload["items"])==len(expected_page)
    assert payload["hasMore"]==(offset+len(expected_page)<len(expected))
    for item,(_,loc) in zip(payload["items"],expected_page):
        assert item["featureId"]=="-".join((loc["country"],loc["party"],loc["source"]))
        assert (item["availableCount"],item["totalCount"])==counts(loc)
        assert abs(item["longitude"]-loc["lon"]) < 1e-7
        assert abs(item["latitude"]-loc["lat"]) < 1e-7
    return payload

# Phase D: HTTP query reads only the selected complete canonical cell;
# verify paging, stable ID ordering, status counts, and filter parity.
large=verify(base,expected_total=1635)
cluster=max((m for m in large["markers"] if m["kind"]=="cluster"),
            key=lambda m:m["locationCount"])
assert cluster["locationCount"]>1000
verify_cell_page(cluster,0,30)
verify_cell_page(cluster,30,30)
verify_cell_page(cluster,cluster["locationCount"]-1,30)
verify_cell_page(cluster,cluster["locationCount"]+2,30)
verify_cell_page(cluster,0,30,{"connectorType":["ccs2"],"mode":["dc"]})
verify_cell_page(cluster,0,0,expected_status=400)
verify_cell_page(dict(cluster,gridX=-1),0,20,expected_status=400)
print("Phase D paginated read-only overview cell HTTP/Npgsql/filter integration PASS")
print("Phase C genuine overview HTTP/JSON/Npgsql/coverage integration PASS")
