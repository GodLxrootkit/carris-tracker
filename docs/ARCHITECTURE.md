# Architecture notes

Developer-facing detail behind [the README](../README.md): how arrivals are predicted, what the two
data sources actually give us, and which parts are deliberately approximate.

## Live predictions

The Carris Metropolitana API returns `estimated_arrival_unix: null` for every stop — there are no
arrival estimates, only the timetable. But `/vehicles` *is* real-time, so the app predicts arrivals
itself:

- each circulating vehicle is placed on its pattern path (`/patterns`, which carries the cumulative
  km of every stop);
- remaining distance is divided by current speed, plus 20 s dwell per intermediate stop;
- vehicles slower than 12 km/h (at a light) fall back to that floor;
- positions older than 3 min are dropped.

Each prediction is paired with the nearest scheduled run of the same pattern, so the UI can show
the live countdown next to `previsto 07:04` when the bus is off schedule. Accuracy is roughly ±5
min in city traffic — there is no traffic model. See the `ponytail:` note in `Eta.kt`.

CARRIS is the awkward case. It publishes no `speed` field, so its arrivals are estimated from
measured movement instead (`Motion.kt`): straight-line distance over a sliding window of the last
three position fixes. Two fixes are enough to measure anything; below 8 m of movement they are
treated as GPS noise rather than a bus crawling.

Which way a bus is going never needed estimating. The feed names the pattern, and a pattern is one
direction of one line, so 716 reads `Alameda D. A. Henriques` one way and
`Benfica - Al. Pé. Álvaro Proença` the other, exactly as published.

## Chosen lines

Lines are no longer hardcoded. `Api.allLines()` fetches every Carris Metropolitana line once per
process (717 lines, ~39 KB gzipped) and `CarrisGtfs.allRoutes()` reads them out of the bundled
asset, both namespaced, giving Settings one list to search.

The choice is persisted in `SharedPreferences` under `lines`, and is refused when it would be
empty. Defaults are the four original Carris Metropolitana lines plus CARRIS 716 and 746, in
`CM_TRACKED_LINE_IDS` / `CARRIS_TRACKED_LINE_IDS`.

Routes whose distance data the feed gets wrong are listed in the asset's `excluded` set and shown
disabled with the reason, rather than offering a line whose predictions would be nonsense.

Adding a line no longer means touching code: it is a checkbox. The only thing that still needs care
is `CARRIS_TRACKED_LINE_IDS`, which uses **route ids, not short names**:

```kotlin
val CARRIS_TRACKED_LINE_IDS =
    listOf("164_0", "108_0", "108_3").map { nsId(PROVIDER_CARRIS, it) }
    // 164_0 = 716 · 108_0 and 108_3 are both 746
```

**List every `route_id` of the line.** A rider-facing line can be several: 746 is `108_0`
(Marquês Pombal – Estação Damaia) and `108_3` (Rossio – Sul e Sueste). Across the feed 41 of 113
lines have 2+ `route_id`s — 727 has six — and a missing variant means that part of the line never
appears. 716 happens to be a single `route_id`, which is why it looks simpler.

## Performance

The stop catalog is 12 752 stops / 6.5 MB of JSON with no server-side search, so:

- it is cached to a flat file in `cacheDir` and reused for 7 days instead of being refetched on
  every cold start;
- stop names are normalised (NFD, accents stripped) **once per catalog**, not once per keystroke —
  filtering 12 752 names is now a plain `contains` over pre-lowered strings. The same folding runs
  over the 893 lines in the Settings search;
- nearest-stop search runs on `Dispatchers.Default`, not the main thread;
- map route polylines (~1 700 points each) are rebuilt only when the routes change, not on every
  recomposition.

`/vehicles` is the map's whole cost: ~792 KB of JSON per call, so the poll interval dominates
everything else. The API serves it gzipped — **109 KB**, a 7.3x saving — and the client now asks
for it, which it has to do explicitly since `HttpURLConnection` will not.

| Poll | Rate | Payload | Per hour |
|---|---|---|---|
| Carris Metropolitana | 9 s | 109 KB gzip | ~44 MB |
| CARRIS (no gzip offered) | 15 s | 48 KB | ~11 MB |

Going below ~6 s cannot show anything the feed does not already have. Timetables are a separate,
much slower loop: one request per saved stop, every 20 s.

## Two providers

| | Carris Metropolitana | CARRIS |
|---|---|---|
| Covers | the network outside Lisbon | Lisbon municipality |
| Lines | all 717 | all 176 |
| Static | `api.carrismetropolitana.pt/v2` (JSON) | `gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS` |
| Live | `/vehicles` (JSON) | `/GTFS/realtime/vehiclepositions` (protobuf) |
| Auth | none | none |

Line ids are namespaced `cm:<id>` / `carris:<id>` (`Api.kt`) so the two id spaces cannot collide
in the pattern cache. Users only ever see short names, via `shortNameOf`.

## The CARRIS feed

The static feed is a 47 MB zip with a 144 MB `stop_times.txt`, which is not something to parse on a
phone. `tools/build_carris_asset.py` distils all 176 routes into **three** assets, split so the
phone only parses what the screen in front of it needs:

| File | Size | Loaded for |
|---|---|---|
| `carris_lines.json` | 1.4 MB | startup: routes, patterns, stops, cumulative km |
| `carris_times.json` | 4.1 MB | Paragens tab only: scheduled departures |
| `carris_shapes.json` | 2.7 MB | map only: route geometry |

Each is loaded lazily on first use. 161_0 ("54E - Elevador Sta. Justa") is excluded because its
distance data is wrong; it ships in `excluded` so Settings can say so.

Keeping it fresh is automated: **`.github/workflows/refresh-carris-asset.yml`** runs daily at
05:23 UTC, probes the gateway's `Content-Disposition` for the feed date, and does nothing unless
the feed actually changed — then regenerates, runs the tests, and commits. Live positions need none
of this; they come from GTFS-RT at runtime.

Live positions come from GTFS-RT protobuf and are parsed by `GtfsRt.kt`, which reads the wire
format directly to avoid adding a protobuf dependency to a minified build. Field numbers were
taken from a live capture and are pinned by `RealFeedTest`.

Two limitations worth knowing:

- CARRIS publishes **only** `vehiclepositions`. There is no `tripupdates`, so there are no
  server-side arrival estimates; CARRIS ETAs are predicted from positions like the others.
- CARRIS positions carry **lat/lon only** — no speed, no bearing — so predictions for CARRIS use
  the `Eta.MIN_SPEED_KMH` floor of 12 km/h and are less precise than Carris Metropolitana's.

## Notes

- Countdowns use the device clock — set Europe/Lisbon.
- Red pill = predicted from a live vehicle; plain time = timetable only.
- Saved stops and line choices are stored on the device (SharedPreferences).
- Map tiles © OpenStreetMap contributors.
- Location is requested only when you tap "nearest stop" or "my location", and every read is
  null-safe, so denying it costs nothing but the two features that need it.

## Code

```
Api.kt             HTTP + JSON client, namespaced line identity, line lists
GtfsRt.kt          GTFS-RT VehiclePosition protobuf reader (unit-tested)
CarrisGtfs.kt      CARRIS bundled asset + live feed -> the same models
Schedule.kt        countdown/time formatting (unit-tested)
Timetable.kt       scheduled departures from the bundled asset (unit-tested)
Eta.kt             live-vehicle arrival prediction (unit-tested)
Motion.kt          speed measured from successive positions, for CARRIS (unit-tested)
AppState.kt        ViewModel: saved stops, line selection, polling, predictions, shapes
MainActivity.kt    theme, tabs, collapsible and re-orderable Paragens screen, stop picker
MapScreen.kt       osmdroid map + BusOverlay
SettingsScreen.kt  about, line picker, update check
Update.kt          GitHub release lookup, download, signature verification
tools/             build_carris_asset.py - regenerates the CARRIS assets from the GTFS zip
```

## Releasing

Version lives in `app/build.gradle.kts`. The published APKs are signed with a keystore that is
**not in this repository** and must not be added to it; `carris.jks` and `keystore.properties` are
gitignored. A release is: bump the version, `./gradlew test assembleRelease`, copy
`app/build/outputs/apk/release/app-release.apk` to `carris-tracker-release.apk`, commit, tag, and
attach the APK to the GitHub release.

Because every published APK is signed with the same key, an update installs over the previous one
without the user uninstalling.

## In-app updates

`Update.kt` checks `releases/latest` on the project's GitHub and compares the tag against the
installed `versionName`, component by component so 2.10 beats 2.9. Needs the repository to be
public; an anonymous caller gets a 404 from a private one.

The safety argument is short and it is the reason the file exists. Before the download is handed to
the installer, `PackageManager.getPackageArchiveInfo` reads the signing certificate out of the APK
**that was just downloaded** and compares it against a fingerprint compiled into the app:

```
4ddcf76febbdec158dc10a7e2c7c2b6d1286938a212db68d51f4abef5074687f
```

If it does not match, the file is deleted and the user is told. A checksum published next to the
download would only prove the file matches whatever was sitting beside it; the certificate proves
the APK was signed by the same key as the running app, which is exactly the condition Android
requires before letting it replace itself. `signingCertificateHistory` is used rather than
`apkContentsSigners` so that rotating the key later does not strand installs that predate it.

Three other things worth knowing:

- nothing is downloaded or installed without the user asking; `REQUEST_INSTALL_PACKAGES` is only
  ever used at the moment of handing the file over, never requested at launch;
- "could not check" and "already up to date" are distinct outcomes, so a network failure never gets
  reported as being current;
- a version the user dismissed is remembered and not offered again until a newer one appears, so
  the startup check does not nag.
