#!/usr/bin/env python3
"""Generate the bundled CARRIS GTFS asset consumed by CarrisGtfs.kt.

The CARRIS feed (https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS) is a 47 MB zip whose
stop_times.txt alone is 144 MB. Parsing that on a phone is not viable, so this script distils
just the tracked routes into a small JSON asset: patterns (stop sequence + cumulative km),
shape geometry, and the stops those patterns call at.

The output is written to app/src/main/res/raw/carris_lines.json.

Re-run whenever the feed version moves. For the current tracked set that is roughly weekly:

    curl -o /tmp/carris-gtfs.zip https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS
    python3 tools/build_carris_asset.py /tmp/carris-gtfs.zip
"""

import argparse
import collections
import csv
import io
import json
import os
import sys
import tempfile
import zipfile

# route_id -> display colour. CARRIS ships an empty route_color for these routes, so the
# values here are ours to maintain. Anything missing falls back to CARRIS red.
ROUTE_COLORS = {
    "164_0": "#C61D23",   # 716 Alameda D. A. Henriques - Benfica
    "108_0": "#1F5FA9",   # 746 Marquês Pombal - Estação Damaia
    "108_3": "#0E7C7B",   # 746 Rossio - Sul e Sueste
}

FALLBACK_COLOR = "#C61D23"

# Coordinate precision for shape points and stop positions. 5 decimals is about 1 m, which is
# far finer than a bus map can show, and 6 decimals (~1 cm) was paying for that in asset bytes:
# at the full 176-route feed the shapes are the largest single section of the file.
COORD_DP = 5

# CARRIS v2.11 GTFS endpoint.
DEFAULT_FEED_URL = "https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS"

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
RAW_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res", "raw")

# The asset is split three ways by access pattern, because the phone parses what it opens and
# 8 MB of JSON through org.json is seconds of startup and a large transient heap - an OOM risk
# on a small phone, not an APK-size problem. The sections land in the files each screen needs:
#
#   carris_lines.json   routes, exclusions, pattern geometry and calendar rules, stops
#                       - needed at startup, for the line list and the line picker
#   carris_times.json   scheduled departures per stop, keyed by stop then pattern
#                       - only the Paragens tab, and only for a stop the user saved
#   carris_shapes.json  polyline geometry, keyed by shape id
#                       - only the map, and only for a line that is selected
LINES_FILE = "carris_lines.json"
TIMES_FILE = "carris_times.json"
SHAPES_FILE = "carris_shapes.json"


def read(zf, name):
    """Yield CSV rows from one file in the GTFS zip."""
    with zf.open(name) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="").read()
    return csv.DictReader(text.splitlines())


def minutes(t):
    """HH:MM:SS -> minutes since midnight.

    GTFS times may exceed 24:00 for after-midnight service (25:10 = 1510). That is kept as an
    offset from midnight of the service day, so a caller can treat values above 1440 as the
    following morning without needing a second list.
    """
    h, m, _ = t.split(":")
    return int(h) * 60 + int(m)


def partition_stop_times(zf, trip_route):
    """Split stop_times.txt into one bucket per route, in a single pass.

    The file decompresses to about 145 MB. Reading it once per route was the generator's whole
    runtime for the full feed; one pass fixes that. Holding every wanted row in memory at once
    would not be safe at 176 routes, so each route's rows are spilled to a temp file as they
    stream past and read back one route at a time. Disk instead of heap, still one pass.
    """
    handles = {}
    paths = {}
    try:
        for row in read(zf, "stop_times.txt"):
            route_id = trip_route.get(row["trip_id"])
            if route_id is None:
                continue
            handle = handles.get(route_id)
            if handle is None:
                fd, path = tempfile.mkstemp(prefix=f"st_{route_id}_", suffix=".csv")
                paths[route_id] = path
                handle = os.fdopen(fd, "w", encoding="utf-8", newline="")
                handles[route_id] = handle
                # Must match the four columns written below, in order: only these are used
                # downstream, and DictReader maps by position, not by name.
                handle.write("trip_id,stop_id,stop_sequence,arrival_time,shape_dist_traveled\n")
            handle.write(
                f'{row["trip_id"]},{row["stop_id"]},{row["stop_sequence"]},'
                f'{row["arrival_time"]},{row["shape_dist_traveled"]}\n'
            )

        for handle in handles.values():
            handle.close()

        buckets = {}
        for route_id, path in paths.items():
            buckets[route_id] = collections.defaultdict(list)
            with open(path, encoding="utf-8", newline="") as fh:
                for row in csv.DictReader(fh):
                    buckets[route_id][row["trip_id"]].append(row)
        return buckets
    finally:
        for path in paths.values():
            try:
                os.unlink(path)
            except OSError:
                pass


def derive_patterns(route_id, trips, stop_times, stop_names, service_rules):
    """Group a route's trips into patterns by identical stop sequence.

    The CARRIS feed has no patterns.txt (it is a merged multi-operator feed), so the pattern
    concept has to be recovered: trips that call at exactly the same stops in the same order
    are the same pattern. In practice this collapses to one pattern per direction.

    Each stop also carries its scheduled minutes-of-day, taken across every trip in the
    pattern. Without these the Paragens tab has no timetable to show for a CARRIS line and
    falls back to "Sem passagens previstas" whenever no bus happens to be circulating.

    [stop_times] is this route's slice of stop_times.txt, handed in by [partition_stop_times].
    It used to be read from the zip in here, once per route: 176 routes meant re-opening and
    re-parsing a 145 MB CSV 176 times, over half an hour of work that is quadratic in the number
    of routes and gets worse every time CARRIS adds one.
    """
    by_signature = collections.defaultdict(list)
    for tid, rows in stop_times.items():
        rows.sort(key=lambda r: int(r["stop_sequence"]))
        signature = tuple(r["stop_id"] for r in rows)
        by_signature[signature].append((tid, rows))

    patterns = []
    for signature, grouped in by_signature.items():
        tid, rows = grouped[0]
        # Scheduled times per stop, unioned over every trip sharing this pattern.
        times_by_stop = collections.defaultdict(set)
        for _, other_rows in grouped:
            for r in other_rows:
                t = r.get("arrival_time", "")
                if t:
                    times_by_stop[r["stop_id"]].add(minutes(t))

        stops = []
        for r in rows:
            km = r.get("shape_dist_traveled", "")
            stops.append(
                {
                    "stopId": r["stop_id"],
                    "sequence": int(r["stop_sequence"]),
                    # Cumulative km from the pattern origin; Eta.progressKm divides the
                    # remaining distance by a speed in km/h, so this column is load-bearing.
                    #
                    # GTFS `shape_dist_traveled` is metres and the Carris Metropolitana API
                    # `path[].distance` is kilometres. Converting here keeps the asset in the
                    # same unit as the other provider - without it every CARRIS prediction is
                    # 1000x too slow, because 164_0 is 7851 m and would read as 7851 km.
                    "km": round(float(km) / 1000.0, 3) if km not in ("", None) else 0.0,
                    # Scheduled departures here, in minutes since midnight of the service day.
                    "times": sorted(times_by_stop[r["stop_id"]]),
                }
            )

        # Which calendar rules cover this pattern's trips, so the app can tell whether it runs
        # today instead of listing every departure of every weekday.
        services = sorted({trips[t]["service_id"] for t, _ in grouped if "service_id" in trips[t]})
        rules = [service_rules[s] for s in services if s in service_rules]

        patterns.append(
            {
                # The shape id doubles as the pattern id.
                #
                # It is NOT unique: a route split into a full run and a short turn gives both
                # variants the same shape_id, so two entries here can share an id while their
                # stop lists differ (75_0_ASC_shp is a 35-stop 1004-trip run and a 21-stop 81-trip
                # short turn). That is deliberate and the reader copes - it collapses them to the
                # variant with the most trips, and the timetable below is keyed on the shared id,
                # so renaming them would drop the short turn's departures from the Paragens tab.
                # Do not make these unique.
                "id": trips[tid].get("shape_id") or f"{route_id}_{len(patterns)}",
                "lineId": route_id,
                # trip_headsign is empty throughout the CARRIS feed, so the destination
                # stop is the only honest label available. With 746 running two route_ids
                # to different termini, the destination is what tells the variants apart.
                "headsign": stop_names.get(stops[-1]["stopId"], ""),
                "shapeId": trips[tid].get("shape_id"),
                "direction": int(trips[tid].get("direction_id") or 0),
                "stopCount": len(stops),
                "tripCount": len(grouped),
                "rules": rules,
                "stops": stops,
            }
        )
    patterns.sort(key=lambda p: p["id"])
    return patterns


# No Lisbon bus route is longer than 50 km. `shape_dist_traveled` is in metres, so a pattern
# recording 7851 in the km field means the /1000 conversion was lost, and every arrival
# prediction for that line would be 1000x too slow. The upper bound is what catches that.
#
# The lower bound only has to catch a missing or zero distance, and it cannot be 1 km: the feed
# contains genuinely short services. Route 97 "PMR - Mobilidade Adaptada" is a demand-responsive
# adapted-mobility circuit whose whole shape is 588 m.
#
# A route that fails either bound is excluded rather than shipped. The feed is not clean — route
# 161_0 "54E - Elevador Sta. Justa" is an elevator with a 2-point, 32 m shape and 2 548 trips
# attached to it — and one broken route must not stop the other 175 being generated. Excluded
# ids go into the asset so the app can list those lines as unavailable instead of showing
# confident nonsense derived from them.
_MIN_KM, _MAX_KM = 0.05, 50.0


def _plausible(patterns):
    """Split patterns into (usable, rejected) by whether their distances make sense."""
    usable, rejected = [], []
    for p in patterns:
        length = p["stops"][-1]["km"]
        if _MIN_KM <= length <= _MAX_KM:
            usable.append(p)
        else:
            rejected.append((p, length))
    return usable, rejected


def load_services(zf):
    """Resolve calendar.txt + calendar_dates.txt into a per-service rule.

    A service is either a weekday mask within a date range, or a list of one-off dates (the
    seasonal services in this feed are calendar_dates-only, with no weekday row at all).
    """
    weekday = collections.defaultdict(set)
    ranges = {}
    for row in read(zf, "calendar.txt"):
        sid = row["service_id"]
        for i, name in enumerate(
            ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
        ):
            if row.get(name) == "1":
                weekday[sid].add(i)
        ranges[sid] = (row["start_date"], row["end_date"])

    added = collections.defaultdict(set)
    removed = collections.defaultdict(set)
    for row in read(zf, "calendar_dates.txt"):
        sid, date, kind = row["service_id"], row["date"], row["exception_type"]
        if kind == "1":
            added[sid].add(date)
        elif kind == "2":
            removed[sid].add(date)

    rules = {}
    for sid in set(weekday) | set(added) | set(removed):
        start, end = ranges.get(sid, ("19700101", "20991231"))
        mask = 0
        for d in weekday.get(sid, ()):
            mask |= 1 << d
        rules[sid] = {
            "days": mask,
            "start": start,
            "end": end,
            "dates": sorted(added.get(sid, set())),
            "removed": sorted(removed.get(sid, set())),
        }
    return rules



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("gtfs_zip", help="path to the downloaded CARRIS GTFS zip")
    ap.add_argument(
        "--routes",
        default="",
        help="comma separated route_ids to include; empty means every route in the feed",
    )
    ap.add_argument("--out-dir", default=RAW_DIR, help="directory to write the three asset files to")
    args = ap.parse_args()

    wanted = [r.strip() for r in args.routes.split(",") if r.strip()]

    with zipfile.ZipFile(args.gtfs_zip) as zf:
        feed_info = next(iter(read(zf, "feed_info.txt")))
        feed_version = feed_info.get("feed_version", "")

        every_route = {r["route_id"]: r for r in read(zf, "routes.txt")}
        # No --routes means the whole feed, so a bare invocation cannot quietly shrink the app
        # back to whatever subset it used to default to.
        if not wanted:
            wanted = sorted(every_route)
            print(f"no --routes given: including all {len(wanted)} routes in the feed")

        missing = [r for r in wanted if r not in every_route]
        if missing:
            sys.exit(f"route_id(s) not in feed: {missing}")
        routes = {rid: every_route[rid] for rid in wanted}

        all_trips = collections.defaultdict(dict)
        for row in read(zf, "trips.txt"):
            if row["route_id"] in wanted:
                all_trips[row["route_id"]][row["trip_id"]] = row

        # Stop names are needed up front to fill in pattern headsigns.
        stop_names = {r["stop_id"]: r.get("stop_name", "") for r in read(zf, "stops.txt")}
        service_rules = load_services(zf)

        # One pass over the 145 MB file, bucketed per route. See partition_stop_times.
        trip_route = {}
        for route_id, trips in all_trips.items():
            for tid in trips:
                trip_route[tid] = route_id
        stop_times_by_route = partition_stop_times(zf, trip_route)

        patterns = []
        rejected = []
        for route_id in wanted:
            derived = derive_patterns(
                route_id,
                all_trips[route_id],
                stop_times_by_route.get(route_id, {}),
                stop_names,
                service_rules,
            )
            usable, bad = _plausible(derived)
            patterns.extend(usable)
            rejected.extend(bad)

        if rejected:
            print("excluded routes with unusable distance data:")
            for p, length in rejected:
                print(f"  {p['id']} ({p['lineId']}): {length} km")

        wanted_shapes = {p["shapeId"] for p in patterns if p["shapeId"]}
        shapes = collections.defaultdict(list)
        for row in read(zf, "shapes.txt"):
            if row["shape_id"] in wanted_shapes:
                shapes[row["shape_id"]].append(
                    (
                        int(row["shape_pt_sequence"]),
                        round(float(row["shape_pt_lat"]), COORD_DP),
                        round(float(row["shape_pt_lon"]), COORD_DP),
                    )
                )
        # Order by shape_pt_sequence: a polyline sorted by anything else draws as garbage.
        shapes = {k: [[lat, lon] for _, lat, lon in sorted(shapes[k])] for k in sorted(shapes)}

        # Every stop any included pattern calls at, with coordinates and its lines.
        stop_lines = collections.defaultdict(set)
        for p in patterns:
            for s in p["stops"]:
                stop_lines[s["stopId"]].add(p["lineId"])

        stops = []
        for row in read(zf, "stops.txt"):
            sid = row["stop_id"]
            if sid not in stop_lines:
                continue
            lat, lon = row.get("stop_lat", ""), row.get("stop_lon", "")
            if not lat or not lon:
                continue
            stops.append(
                {
                    "id": sid,
                    "name": row.get("stop_name", ""),
                    "lat": round(float(lat), COORD_DP),
                    "lon": round(float(lon), COORD_DP),
                    "lineIds": sorted(stop_lines[sid]),
                }
            )
        stops.sort(key=lambda s: s["id"])

    out_routes = []
    for route_id in wanted:
        r = routes[route_id]
        out_routes.append(
            {
                "id": route_id,
                "shortName": r.get("route_short_name", ""),
                "longName": r.get("route_long_name", ""),
                "color": ROUTE_COLORS.get(route_id) or r.get("route_color") or FALLBACK_COLOR,
            }
        )

    # Scheduled departures move out of the pattern geometry and into their own file, keyed by
    # stop then pattern. Keying by pattern is what keeps "only show the lines I selected"
    # possible: a stop called by two routes has two different sets of departures.
    times_by_stop = collections.defaultdict(dict)
    for p in patterns:
        for s in p["stops"]:
            mins = s.pop("times", None)
            if mins:
                times_by_stop[s["stopId"]][p["id"]] = mins

    header = {
        "feedVersion": feed_version,
        # The window this snapshot is good for. `refresh-carris-asset.yml` reads these to decide
        # whether it is worth downloading the feed at all, so they have to travel with the asset:
        # the only way to learn them is to open the 45 MB zip, which is exactly what we are trying
        # to avoid on most days.
        "feedStartDate": feed_info.get("feed_start_date", ""),
        "feedEndDate": feed_info.get("feed_end_date", ""),
        "source": DEFAULT_FEED_URL,
    }

    lines_asset = dict(
        header,
        # Lines the feed has, minus the ones it gets wrong. The app lists these so they can be
        # shown as unavailable rather than silently missing or, worse, rendered with wrong ETAs.
        excluded=[
            {"id": p["id"], "lineId": p["lineId"], "km": length} for p, length in rejected
        ],
        routes=out_routes,
        patterns=patterns,
        stops=stops,
    )
    times_asset = dict(header, stopTimes=times_by_stop)
    shapes_asset = dict(header, shapes=shapes)

    os.makedirs(args.out_dir, exist_ok=True)
    written = []
    for name, payload in (
        (LINES_FILE, lines_asset),
        (TIMES_FILE, times_asset),
        (SHAPES_FILE, shapes_asset),
    ):
        path = os.path.join(args.out_dir, name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, separators=(",", ":"), ensure_ascii=False)
        written.append((name, os.path.getsize(path)))

    times = sum(len(v) for per_pattern in times_by_stop.values() for v in per_pattern.values())
    print(
        f"feed {feed_version}: {len(out_routes)} route(s), {len(patterns)} pattern(s), "
        f"{len(stops)} stop(s), {sum(len(v) for v in shapes.values())} shape point(s), "
        f"{times} scheduled departures"
    )
    if rejected:
        print(f"  {len(rejected)} pattern(s) excluded for unusable distance data")
    for name, size in written:
        print(f"  {name:22} {size / 1024:9.1f} KB")
    print(f"  {'total':22} {sum(s for _, s in written) / 1024:9.1f} KB")


if __name__ == "__main__":
    main()