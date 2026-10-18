# Changelog

All notable changes to Carris Tracker. Dates are ISO-8601.

## [2.5.1] – 2026-10-18

First weekly automated release. No code changes — it exists to deliver the CARRIS data that was
regenerated during the week, which was being refreshed daily but never reaching any phone, because
the data lives inside the APK. From now on this happens on Sundays without anyone doing it by hand.

## [2.5.0] – 2026-10-18

First public release. The repository history and previous tags have been squashed away, so this
and the earlier entries below are the only record of what changed.

### Added

- A note at the top of the README saying what this is: a hobby project, vibe coded, unsupported.

The bug fixes in 2.3.1 are all still in place; see that entry.

## [2.3.1] – 2026-10-18

A pass over the code for bugs we had not caught. Several of these produced wrong numbers rather
than an error, which is the worst kind.

### Fixed

- **Lines whose geometry was wrong.** The CARRIS asset carries two patterns under a few shared ids:
  a route split into a full run and a short turn, which inherit the same `shape_id`. The live feed
  and the asset looked those up with different rules, so the same bus was measured against one
  geometry by the map and another by the ETA. Reachable from 2.2.0, which let you pick any of the
  176 routes: lines 75, 171 and 208 were affected. Both lookups now resolve to the same pattern.
- **A bus 15 km away could show "agora".** A pattern missing one stop's distance yields NaN, which
  fails every comparison - so it passed the "already passed" check, and `NaN.toInt()` is 0 seconds.
- **Polling ran in the background.** The vehicle feeds were polled for the life of the process even
  with the app closed, at ~44 MB/hour from Carris Metropolitana alone. Polling now waits while the
  app is off screen.
- **Circular lines lost their second lap.** 29 of the 309 patterns visit a stop twice, and the stop
  was always resolved to the first occurrence - which on the second lap is one the bus has already
  served. A bus two minutes away showed no countdown. The GTFS-RT `current_stop_sequence` field was
  being discarded; it is the only thing that says which lap a bus is on, so it is now read.
- **Asset parsing ran on the main thread**, and the caches were check-then-act with no lock, so
  three saved stops meant reading and parsing the 4.1 MB timetable three times over. Cold starts
  should be noticeably quicker.
- The GTFS-RT decoder skipped a length field with no bounds check, where its sibling read had one.
  A single corrupt byte could throw out of the parse and silently drop **every** CARRIS bus for that
  poll. A length of exactly 2^32 also truncated to zero and left the reader mid-payload.
- A cross-network stop id collision meant one stop was silently dropped from the picker. Both
  networks number their stops independently and `7822` exists on both.
- A stop in the Paragens tab whose arrival request failed could leak the raw endpoint URL into the
  error banner.

### Changed

- Departures are built from a cleared `Calendar`. It was lenient, so asking for a February date from
  a 31 March date landed on 28 March. Not reachable today, since the only caller passes today's date.

## [2.3.0] – 2026-10-18

The app can now update itself from inside, which is what the stub was pointing at in 2.2.0.

### Added

- **In-app updates.** An optional check against the project's GitHub releases, run when the app
  opens or on demand from Definições. A newer version is offered for download; nothing is
  downloaded or installed without the user saying so.
- The downloaded APK is checked against our signing certificate before it is offered. A file that
  is not signed with our key is deleted, not installed. This is a stronger check than publishing a
  checksum, which would only prove the file matches whatever sat next to it.
- A version the user turned down is remembered, so the startup check does not keep asking.

### Added, but you may not see it yet

- Updates only reach installs that already have 2.3.0, since 2.2.0 has no updater in it. Everyone
  else picks this up from the releases page as usual.
- The check needs the repository to be public. While it is private, GitHub returns 404 to an
  anonymous caller and the app says so instead of falsely claiming it is up to date.

## [2.2.0] – 2026-10-18

A Settings tab, and with it the ability to choose which lines the app follows.

### Added

- **Settings tab** with an About section showing the installed version, read from the package
  manager so it cannot drift from what actually shipped.
- **Line picker** covering all 717 Carris Metropolitana lines and all 176 CARRIS routes, from both
  providers in one searchable list. Previously the six tracked lines were hardcoded.
- **Search that ignores accents.** The feed says "Alcácer" and "Algács"; typing "alcacer" or
  "algacs" finds them, because marks are folded away before matching.
- Selection persists across restarts and is refused when empty, so the app can never end up
  tracking nothing.
- An **auto-update** row, disabled and labelled as such. It does nothing yet; the switch is greyed
  out rather than pretending to work.

### Changed

- Buses on the map, arrivals at saved stops, and the route filters all follow the chosen lines
  instead of a fixed list.
- Lines with broken distance data in the feed, currently CARRIS route 161_0 "54E - Elevador Sta.
  Justa", appear greyed out and disabled with the reason shown, rather than offering a line whose
  predictions would be wrong.

### Fixed

- Deselecting a line now reloads the pattern and shape catalogue on a background coroutine. The
  refresh used to be invoked from the click handler, which blocked the UI thread.
- An orphaned brace in the API layer closed the enclosing object early and broke the build; caught
  by the next compile.

## [2.1.0] — 2026-10-18

All 176 CARRIS routes are now in the app's data, split so the phone only parses what a screen needs. A Settings tab to pick them is next.

### Added

- **Every CARRIS route is in the bundled asset** — 176 routes, 309 patterns, 2 344 stops, 946 291
  scheduled departures, up from 3 routes. Route 161_0 "54E - Elevador Sta. Justa" is **excluded**:
  the feed gives it a 2-point, 32 m shape with 2 548 trips attached, and its distances would make
  every prediction on it nonsense. Exclusions are recorded in the asset so the app can list such
  lines as unavailable rather than rendering wrong numbers. Route 97 "PMR - Mobilidade Adaptada"
  is kept: at 588 m it is genuinely that short, and the guard's 1 km floor was rejecting it.

  Nothing user-visible yet — the line picker that selects among them is the next release.

### Fixed

- **The asset generator read `stop_times.txt` once per route.** 176 routes meant re-opening and
  re-parsing a 145 MB CSV 176 times: over 24 minutes, and quadratic in the route count, so it got
  worse every time CARRIS added a line. It is now a single streaming pass spilled per route to
  temp files, so memory stays flat. **Full feed: 24+ min and never finished → 30 s.** Three
  routes: 46 s → 9 s.

- **The weekly refresh now does nothing when the feed has not moved.** CARRIS names the feed for
  the day it covers in its response headers (`filename=gtfs_2026-10-17.zip`), so the date is
  readable in ~0.1 s without downloading anything. `HEAD` is refused (405) and `Range` is ignored
  (a range request gets the whole 46 MB), but the headers arrive before the body. If the header
  ever goes missing it falls back to a full download rather than skipping silently.

### Changed

- **The asset is three files, split by which screen needs them.** At the full feed they are
  1.4 MB, 4.2 MB and 2.7 MB. Parsing all 8 MB at startup through `org.json` is seconds of delay
  and a transient heap in the hundreds of megabytes — an OOM risk on a small phone, not an
  APK-size problem. So:
  - `carris_lines.json` — routes, exclusions, pattern geometry and calendar rules, stops. Parsed
    at startup, for the line list.
  - `carris_times.json` — departures per stop then pattern. Only when the Paragens tab needs a
    timetable for a stop the user saved.
  - `carris_shapes.json` — polyline geometry. Only when a selected line has to be drawn.

  Departures are keyed by stop *and* pattern so a stop served by two lines keeps both sets, and
  "only show the lines I selected" stays possible.

- **Shape coordinates are rounded to 5 decimals** (~1 m) instead of 6 (~1 cm), which a bus map
  cannot show. 184 KB → 178 KB on the small asset, and the full feed's shapes are the largest
  single section.

- `python3 tools/build_carris_asset.py <zip>` with no `--routes` now means **every route in the
  feed**. It defaulted to three ids, so a bare invocation would have quietly shrunk the app back
  to 716 and 746.

**APK: 1.3 MB → 2.86 MB** for all 176 routes.

## [2.0.2] — 2026-10-15

Your stops are on the map, the map opens at a usable zoom, and CARRIS speed estimates work at all.

### Fixed

- **CARRIS speed estimates never appeared.** Both providers share one `MotionTracker` and are
  polled on different clocks — 9 s and 15 s — but each call pruned the tracker by membership, so
  the CM poll deleted every CARRIS window and the CARRIS poll deleted every CM one. A window needs
  two samples, so a CARRIS bus could never keep one across a 9 s poll and the estimate was always
  null. This was a regression from splitting the single poll loop in 1.11.0, and it looked like a
  data problem rather than a bug because Carris Metropolitana reports its own speed and was
  unaffected.

  Pruning is now by age only, which the tracker already did and which still bounds the map. A new
  test has one provider polling between the other's samples and asserts the window survives.

### Added

- **Your saved stops are drawn on the map.** They were never drawn at all. The only stop text on
  the map was the name under each *bus* dot, of the stop that bus is heading to, which reads at a
  glance like a saved stop whenever a bus happens to be near one.

  A round blob was the first attempt and was wrong for the same reason the bus dots are not
  enough — it looked like another bus. It is a sign with a pointer, in the app's red, carrying a
  bus pictogram and the stop's name, so it reads as a place, points at the exact coordinate, and
  says what it marks. Drawn above the route lines and below the buses, so a bus is never hidden.

### Changed

- **The map opens at zoom 15 instead of 12.** The first frame kept the `MapView` constructor's
  zoom, which frames most of metropolitan Lisbon — fine for seeing the whole network, useless for
  finding the stop you are standing at. The zoom is now set together with the initial centre, and
  only when there is something to centre on, so it never pins a closer zoom to the wrong part of
  the map.

## [2.0.1] — 2026-10-15

"Todas" means our six lines again, and 1721 draws its whole route.

### Fixed

- **"Todas" on the map drew every bus on both networks** — around 500 dots for lines the app does
  not track. `visibleVehicles` returned the list unfiltered when no line chip was selected, while
  selecting a chip filtered correctly, which made it look like a chip problem rather than a data
  one. Neither feed filters for us: Carris Metropolitana served ~1 000 vehicles and CARRIS ~560,
  network-wide.

  The filter now happens where the data enters, so "Todas" is correct by construction and the chip
  branch has nothing left to get wrong. It also stops the map carrying ~1 500 vehicles to draw
  ~100, and keeps the per-line bus counts on the chips from including lines that have no chip.

- **1721 drew a fifth of its route.** One shape is drawn per line, taken from the first pattern,
  and for 1721 the first is a 14-stop 2.9 km stub of a route that is 8.4 km on its other variants.
  Every other line happened to list a near-complete pattern first, which is why only 1721 looked
  wrong. It now picks the pattern with the most stops, which matched the largest shape on three of
  the four lines and picked a full-length sibling on the fourth — and costs nothing, since the stop
  list is already parsed, whereas comparing shapes would mean fetching all of them.

  This also applies to CARRIS now that its patterns load.

### Changed

- **CARRIS buses show their speed, marked as estimated.** They publish no speed field, so the
  number is derived from successive positions; the card says `38 km/h · estimado` where
  Carris Metropolitana says `38 km/h`. It stays absent until two polls have been seen and below
  8 m of movement, rather than showing a stale guess for a bus that is not moving.

- **The AVL vehicle number is off the tapped-bus card.** It shared a line with the speed as
  `38 km/h · 2794`. It matters to operators and to complaints, and to nobody waiting at a stop; it
  stays on the model, where the speed windows are keyed by it.

## [2.0.0] — 2026-10-15

The tapped-bus card is rebuilt around the bus's destination, and CARRIS stops working.

This is a major bump because the headline fix changes what the app can tell you: line 716 and
746 had **no arrival predictions, no destination and no route on the map** for eleven releases,
because their patterns never loaded. Both providers now behave the same way.

Going forward this is a patch line: 2.0.1, 2.0.2, and so on.

### Fixed

- **CARRIS patterns never loaded at all.** Two id mismatches, both since CARRIS was added in 1.1.0:
  `CarrisGtfs.lines()` returned *bare* pattern ids while every other id in the app is namespaced
  (`carris:…`), so `loadPattern` matched none of them and returned null; and `CarrisGtfs.pattern()`
  compared its argument against namespaced ids while being called with a bare one, so it could
  never match either.

  Both fail by returning null, which reads as "no data yet" rather than "wrong key", and that is
  why it survived so long. The consequences, all for 716 and 746 only:

  - **no headsign**, so tapping a bus showed `em circulação` instead of its destination
  - **no arrival predictions** — `Eta` had no pattern to project the bus onto
  - `busStopHint` always answered `None`, so the tapped-bus card never showed a countdown to a
    saved stop or the "other direction" warning
  - no `shapeId`, so **716 and 746 drew no route polyline** on the map

  `CarrisPatternLookupTest` now asserts the join itself — every pattern id a line advertises must
  resolve back to itself — against the real bundled asset. Verified it goes red on the old code.

### Changed

- **The tapped-bus card leads with the destination, always.** It previously led with the
  countdown to your stop when there was one, and only showed the headsign otherwise, so the
  destination disappeared exactly when a bus was relevant to you. The destination is now the
  header and the countdown sits under it.

- **Carris Metropolitana buses show their speed again**, on the line under the destination. CARRIS
  is excluded deliberately: it publishes no speed field at all, so any number there would be
  invented.

- **The "sentido contrário" warning is gone.** It said which way a bus was running relative to
  your stops, which is now the header, in a form that also covers lines with no saved stop
  nearby. The countdown to your stop is kept — the destination does not replace that.

## [1.11.0] — 2026-10-15

Buses say where they terminate instead of a compass point, and the map polls each provider at
the rate that provider actually publishes at.

### Changed

- **Tapping a bus shows its final destination.** 716 reads `Alameda D. A. Henriques` one way and
  `Benfica - Al. Pe. Álvaro Proença` the other; 746 reads `Estação Damaia`, `Marquês Pombal` or
  `Sul e Sueste`. This comes from the vehicle's pattern, so it is exact — no estimation needed,
  which is why it replaced the compass point added in 1.10.0. The map dot's heading tick is gone
  with it, along with the `bearing` plumbing it needed.

- **CARRIS status no longer clutters the bus card.** It reports `IN_TRANSIT_TO` for every one of
  its ~510 buses, permanently, so "em circulação" was never information. It is now only shown when
  the status says something.

- **Two poll clocks, each measured against the feed rather than guessed:**

  | Provider | Interval | Measured publish rate | Why |
  |---|---|---|---|
  | Carris Metropolitana | 9 s | ~6 s | 9 s always lands on new data while skipping some publishes |
  | CARRIS | 15 s | ~12–14 s | the first interval that reliably lands on new data |

  Both were measured by polling the live feeds and counting buses that moved more than 10 m
  between fetches. CARRIS served the same bytes on half the fetches at a 6 s interval.

- **The re-order arrows are behind a long-press now.** They sat permanently beside the expand
  and remove controls — four buttons in a card header, for a control used rarely. Long-pressing a
  card enters a re-order mode where the arrows *replace* the expand control rather than joining
  it, so the two never appear together, and the header button becomes "Concluir". With a single
  saved stop the mode cannot be entered at all.

  Drag-and-drop was considered and rejected: `animateContentSize` gives the cards different
  heights, which makes offset-to-index mapping ambiguous, and hand-rolling it would be the
  fiddliest code in the app for a list of two or three items.

### Fixed

- **`/vehicles` is now fetched gzipped.** It is ~792 KB of JSON and the map polls it often, so
  this is the single biggest data cost in the app. The API serves it at 109 KB, a 7.3x saving,
  and `HttpURLConnection` will not ask for it on its own. Combined with the intervals above,
  the map now costs ~55 MB/hour instead of ~690.

## [1.10.0] — 2026-10-12

CARRIS buses show which way they are going, and the map updates twice as often.

### Added

- **Direction for CARRIS buses.** Tapping one now leads with `→ Alameda D. A. Henriques`, taken
  from the vehicle's own pattern. That is exact rather than estimated, and it is what separates
  the two 746 variants or the two halves of a route. CARRIS sends neither `speed` nor `bearing`
  — decoded live, 0 of 561 vehicles carry either field, and all 561 report `IN_TRANSIT_TO` — so
  before this there was no way to tell a bus crawling in traffic from one at a light.

- **Measured heading as a backstop.** A tick on the map dot and a compass point (`NE`) on the
  card, derived from successive positions. Carris Metropolitana still uses the `bearing` its own
  feed reports; CARRIS fills it in from movement once two polls have been seen. `– –` until
  then, which is the honest answer rather than a fake north.

- **Buses can be re-ordered on the Paragens tab.** Up/down arrows that grey out at the ends.
  The order survives a restart; `persistStops` already wrote the list verbatim.

### Changed

- **The map refreshes every 12 s instead of 20 s**, on its own clock. Timetables stay at 20 s
  because they cost one request per saved stop and only move in minutes. Measured the CARRIS
  feed's cadence first: it republishes faster than 10 s, so 12 s is near the floor and going
  tighter would only spend data.

- **CARRIS arrivals are estimated from measured movement.** Previously every CARRIS bus was
  divided by the same 12 km/h floor, so its ETA was a guess with no relation to how fast it was
  actually going. Against the live feed, 30 s of movement measures a median of 9 km/h and a 90th
  percentile of 30 km/h. Speed is used for the estimate, not shown on the card.

### Fixed

- **`Eta.distanceKm` returned metres.** Renamed to `distanceMeters`, because it was read as
  kilometres by new code and produced speeds 1000x too high.

- **`bearing = 0` meant both "due north" and "field absent".** Unknown is now `-1`, so a bus
  heading north reads `N` instead of `– –`.

## [1.9.0] — 2026-10-12

Tapped buses say how they relate to your stops. Also fixes a units bug that made every CARRIS
prediction wrong.

### Fixed

- **Every 716 and 746 arrival prediction was ~1000x too slow.** The two providers disagreed on
  distance units and nothing normalised them: GTFS `shape_dist_traveled` is metres, while the
  Carris Metropolitana API's `path[].distance` is kilometres. `Eta.secondsToStop` divides that
  distance by a speed in km/h, so line 164_0's 7851 m was read as 7851 km and a 20-minute leg
  came out at 11 days.

  The generator now converts, and refuses to run if any route length falls outside 1–50 km, so
  the weekly refresh job fails loudly rather than shipping it. Verified by breaking the
  conversion deliberately: the job exits non-zero naming the pattern and the expected
  division.

  This survived 1.5.0 through 1.8.0 because the ETA tests' own fixtures were written in
  kilometres, so they agreed with the code whatever the data said. It surfaced when the bus-card
  tests used real-world stop distances.

### Added

- **Tapping a bus now says where it is going relative to your stops.** The card leads with the
  answer:
  - `Igreja Benfica · 3 min` when the bus is heading to one of your stops
  - `Passa Igreja Benfica no sentido contrário` when it serves a saved stop's namesake but is
    running the opposite pattern
  - the previous status line when the bus is unrelated to anything you saved

  Matching is by stop id within the vehicle's own pattern, so direction is respected for free.

- **The opposite-direction case is the reason this was worth building.** Every one of the
  reported stops is a directional pair — `Pç. Espanha / Av. Berna` is `1503` towards Benfica and
  `1504` towards Alameda D. A bus never calls at both, so a saved `1503` would silently never
  match a northbound bus and read as a missing feature rather than the wrong direction.

- `busStopHint` — pure and separately testable, using the real stops as fixtures: `030605`,
  `1503`/`1504`, `10903`/`10904`, `1501`/`1502`.
- `DistanceUnitTest` pinning the km contract, including the absurd figure the bug produced.

### Notes

- `Igreja Benfica` exists in both networks: `060041`/`060051` on Carris Metropolitana and
  `10903`/`10904` on CARRIS. The Paragens picker lists four stops with that name; the line
  badge is what tells them apart.
- A bus that has already called at a saved stop shows nothing rather than claiming the wrong
  direction — it did go the right way, it just went already.

- Tests: 90 locally, 95 in CI.

## [1.8.0] — 2026-10-12

Bus dots carry the name of the stop they are heading to, when zoomed in far enough.

### Added

- **The next stop's name is drawn under each bus dot above zoom 15.** `stop_id` in both feeds
  is exactly that — the stop the bus is at when stopped, the one it is travelling towards
  otherwise — and it is what tells you where a bus is going. The line number alone cannot; four
  buses on different parts of a route all read "1717".
  The label is drawn in white with a dark halo, since white text is unreadable over pale map
  tiles and the overlay cannot see what is underneath.
- Names come from the stop catalog the map tab already loads, so there is no extra request. They
  appear once it arrives, which may be a moment after the map.
- `busSubtitle` is a pure function, so the decision is unit tested without a Canvas:
  zoom gate, missing `stop_id`, unknown stop, catalog not loaded, blank names, truncation at
  20 characters, and accented names.

### Notes

- The label is capped at 20 characters with an ellipsis. Dots are small and text does not wrap,
  so full names would overlap; the VehicleCard is the place for an untruncated name.
- Zoom 15 rather than the 14 used for line numbers, because these strings are much longer and
  need more room before they become clutter.
- Checked against real data: **52 of 58** `stop_id` values in a live CARRIS capture resolve
  through `stops.txt`, and all 20 in a Carris Metropolitana capture resolve through `/stops`.
  The 6 that do not are merged-feed ids absent from CARRIS's `stops.txt`; those buses simply
  draw no label. Not verified for 716/746 directly, because neither was in service at the time
  of the check — they run weekdays and Saturdays, and the check was made overnight.

- Tests: 74 locally, 79 in CI.

## [1.7.0] — 2026-10-12

Arrival selection is per line: the next 2 departures for every tracked line.

### Changed

- **`upcoming` returns the next 2 departures per line** instead of the soonest few overall.
  The rule is now "2 for each line you track", which is what the Paragens tab is actually for.

  At R Elias Garcia 103 that renders as:

  ```
  05:12 1717   05:55 1707
  05:15 1717   05:57 1721
                05:58 1707
                05:59 1721
                06:13 1720
                06:38 1720
  ```

  Every line is visible and the card reads as one interleaved list, rather than 1717 filling
  the top and the rest appearing only once its 148 early departures are used up.

- **Grouped by short name, not route id.** 746 is `108_0` and `108_3`, so per-id grouping
  would have given it four rows all badged 746 — the same duplicate-label problem the filter
  chips had in 1.2.0. The two variants now share one allowance.
- `Timetable.select` takes a `lineKey` so the grouping is a parameter rather than baked in, and
  defaults to `lineId` for callers without a line list.
- `upcoming`'s parameter is `perLine`, not `limit`, since it no longer caps the total.

### Added

- `TimetableTest` covering per-line selection with uneven line volumes, a line with fewer
  departures than its allowance, and variant grouping.

- Tests: 65 locally, 70 in CI.

## [1.6.1] — 2026-10-12

Only one of four lines showed at a stop. Caught immediately after 1.6.0.

### Fixed

- **Three of the four tracked lines were invisible at a busy stop.** At R Elias Garcia 103 the
  four lines have 445 future departures between them — 100 for 1707, 148 for 1717, 41 for 1720,
  156 for 1721 — and only 1717 appeared, which read as the app having dropped the rest.

  Not a schedule problem; confirmed against the live feed that all four run today and have
  departures ahead.

  1.6.0 introduced a global cap where arrivals were merged and truncated, and `upcoming` then
  took the soonest few of what survived. A time-sorted `take(n)` is the wrong selection on this
  network: 1717 alone has 148 departures before 07:00, so it filled every slot on its own and
  1707, 1720 and 1721 were cut before anything was displayed. The behaviour predates 1.6.0
  (`upcoming` always took the soonest few) — what 1.6.0 did was make it worse by capping
  further upstream.

  Each line's soonest arrival is now taken first and only the remaining slots are filled in time
  order. Verified against the real 445 arrivals, the stop now shows 1717 at 05:12, 05:15, 05:17,
  05:35 and 05:44, then 1707 at 05:55, 1721 at 05:57 and 1720 at 06:13 — every line present,
  soonest still first.

### Changed

- `Timetable.merge` no longer truncates. It deduplicates; choosing what the user sees is
  `select`'s job, and that needs the full set. It had a cap of 40, which was itself enough to
  drop lines at this stop.
- `Timetable.select` extracted as a pure function, since `upcoming` lives on an `AndroidViewModel`
  and could not be unit tested.
- `upcoming` default limit 5 → 8, so a stop served by four lines has room for one of each plus
  the soonest extras.

### Added

- `TimetableTest` +4 covering selection when one line is far busier, time-order filling,
  identity under the limit, and that merging does not truncate.

- Tests: 64 locally, 69 in CI.

## [1.6.0] — 2026-10-12

Carris Metropolitana stops show their timetable overnight.

### Fixed

- **Every Carris Metropolitana stop read "Sem passagens previstos" when no bus was in service.**
  `R Elias Garcia 103` (030605) is on 1707, 1717, 1720 and 1721 and showed nothing at all.

  `/arrivals/by_stop` is a rolling window, and it can be entirely in the past. Queried at 03:46
  on 2026-10-12 it returned 196 entries — 188 from the previous day, 8 from today up to 01:33 —
  and **none** in the future. Every one was filtered out as elapsed, so the stop rendered empty
  even though the service day had not started.

  The timetable was never missing: `/patterns/{id}` carries every scheduled arrival in
  `trips[].schedule[]` with `arrival_time_24h`, alongside `valid_on` dates. The app already
  downloads that payload at startup for the stop sequence and cumulative km and was discarding
  the rest. It is now parsed, which costs no extra network — `/patterns` was always the heaviest
  thing the app fetches.

  Scheduled departures are now taken from both sources and merged.

  Verified against the live feed: stop 030605 at 03:51 yields 100 future departures from
  05:55 onwards across 1707's patterns, where before it yielded none.

### Added

- `Timetable` — turns a pattern's own schedule into arrivals, and merges arrival sources.
  Departures are only offered from patterns whose `valid_on` covers today.
- `TimetableTest` (10 tests) covering the overnight case, elapsed departures, after-midnight
  service, `valid_on` filtering, and merging.

### Changed

- `PatternInfo` carries `validOn`; `PatternStop.times` is populated for Carris Metropolitana as
  well as CARRIS, so both providers now share one representation of a timetable.

### Notes

- **Merge is an exact match on line, pattern and scheduled second, not a time window.** Both
  sources derive from the same GTFS schedule, so the same bus carries an identical unix time in
  each. A fuzzy window was tried first and was wrong: this network has genuinely distinct
  departures 20–60 s apart (05:37:49 and 05:38:28 on line 716), and a 90 s window merged two
  different buses into one.
- After-midnight service is handled by `arrival_time_24h`, which normalises 00:00–02:59 rather
  than 24:00–26:59, so a 00:20 run correctly appears as still ahead at 00:05.

- Tests: 60 locally, 65 in CI.

## [1.5.1] — 2026-10-12

Fixes a regression from 1.1.0 that made every Carris Metropolitana stop unusable.

### Fixed

- **Carris Metropolitana stops could not be found.** Nearest-stop search returned nothing for
  them and search results showed no line badges, while the CARRIS lines worked normally.

  `Api.allStops()` returns bare Carris Metropolitana line ids (`1707`) and `CarrisGtfs.stops()`
  returns namespaced ones (`carris:164_0`), but both consumers — `nearestStops` and the stop
  picker's badges — compare against namespaced `TRACKED_LINE_IDS`. So every CM stop failed the
  tracked-line test.

  Introduced when the two providers were added in 1.1.0: `vehicles`, `lines`, `arrivalsByStop`
  and `pattern` results were all namespaced, but the stop catalog was missed. It went unnoticed
  because arrivals and ETAs for already-saved stops go through the API rather than the catalog,
  so the rest of the app kept working and only search looked broken.

  Namespacing now happens in `ensureAllStops`, at the single point the two catalogs meet.
  Because it is applied on read as well as on write, a catalog cached by an older build repairs
  itself rather than staying broken until its 7-day expiry.

### Added

- `StopCatalogLineIdTest` (5 tests) pinning that catalog line ids are namespaced, that bare ids
  deliberately do *not* match the tracked list, and that the on-disk format stays bare.

### Notes

- Checked that the two catalogs cannot collide: 81 CARRIS stop ids against 12 752 CM ones, no
  overlap, so the `distinctBy` merge is safe today. Worth re-checking if either feed changes its
  id scheme, since a collision would silently drop the CARRIS stop.

- Tests: 49 locally, 54 in CI.

## [1.5.0] — 2026-10-12

CARRIS stops finally show their timetable.

### Fixed

- **Every CARRIS stop read "Sem passagens previstas" whenever no bus happened to be
  circulating.** This is what 716 and 746 did at any hour of the night, and it looked like
  the line was broken. It was not: the departures were in the feed the whole time.

  The cause was mine. The bundled asset stored each pattern stop as `{stopId, sequence, km}`
  and dropped `arrival_time` from `stop_times.txt`. With no timetable, and CARRIS having no
  arrivals endpoint and publishing no `tripupdates`, the Paragens tab had nothing to show but
  a live prediction — so an empty night produced an empty screen. Carris Metropolitana was
  unaffected because `/arrivals/by_stop` serves its timetable.

  The asset now carries each stop's scheduled minutes-of-day, plus the calendar services, so
  the app can tell whether a pattern runs today.

- **Pattern headsign restored** for CARRIS arrivals. It was lost in 1.4.0's asset regeneration.

### Added

- Scheduled departures for CARRIS stops, filtered to patterns running today and to departures
  from now on. They are plain scheduled times, so the UI labels them "horário" exactly as it
  does for the Carris Metropolitana timetable, and live predictions still overlay them.
- `ServiceRule` — a calendar service resolved from `calendar.txt` plus `calendar_dates.txt`,
  handling weekday masks, one-off seasonal dates (`Agosto_*`, `Verao_*` carry no weekday row)
  and removals. Extracted as its own class so it is unit-testable.
- `CarrisScheduleTest` (9 tests) covering weekday masks, seasonal date-only services,
  removals, validity ranges, after-midnight ordering and the Calendar-to-GTFS weekday mapping.

### Numbers

- Asset 24 KB → 180 KB: 20 812 scheduled departures across 6 patterns, 164 pattern-stops.
- Chosen over a bitmap encoding, which would have been 307 KB — the minute lists are sparse,
  so plain sorted arrays are smaller.
- Tests: 44 locally, 49 in CI.

### Note

After-midnight departures are stored as minutes past 1440, as GTFS allows (`25:10` = 1510),
so they sort after the rest of the evening rather than wrapping back to the small hours.

## [1.4.0] — 2026-10-12

Map layout and controls, plus a test harness for the map tab.

### Fixed

- **The map painted over the status bar and the filter chips.** `AndroidView` had no modifier,
  so the native `MapView` was sized by its own rules and, not being clipped to its Compose
  bounds by default, drew outside them. It now gets `Modifier.fillMaxSize()` inside a
  `Box(Modifier.clipToBounds())`. The frame and chip row moved into `MapLayout`.
- **The re-centre button silently did nothing** whenever the location provider had not
  produced a first fix yet, which is the common case shortly after opening the tab. It now
  says so.
- **The frame button was permanently disabled** for stops saved before coordinates were
  recorded: the backfill only ran when the stop picker was opened, which the map tab never
  does. The map tab now pulls the catalog in.

### Added

- **Layout tests for the map tab** (`MapLayoutTest`, 5 tests) — Robolectric plus Compose UI
  tests that measure the real thing: the filter row stays chip-sized, the map starts below the
  top inset, the map reaches the bottom, 746 yields one chip, and live lines show counts.
  The first is the direct regression test for the 1.3.0 bug.
- **CI** (`.github/workflows/ci.yml`): `testDebugUnitTest` and `assembleRelease` on every push
  to main.

### Notes

- Robolectric cannot run on Termux: it loads a `linux-aarch_64` Conscrypt `.so` and Termux is
  a bionic (Android) JVM, so every Robolectric test dies with `UnsatisfiedLinkError` before
  reaching any of its own code. The layout tests are therefore skipped locally when `PREFIX`
  looks like Termux, and run in CI on `ubuntu-latest`. `build.sh` still passes on the phone;
  `testDebugUnitTest` reports 34 there and 39 in CI.
- The map still has no test coverage of its own behaviour — taps, overlays, the ETA-driven
  dot drawing. What is covered now is the frame layout, which is where the bug was.

## [1.3.1] — 2026-10-12

Fixes a layout bug that broke the map tab in 1.3.0.

### Fixed

- **The chip row filled the whole screen, leaving the map a thin strip.** The provider divider
  added in 1.3.0 used `Modifier.fillMaxHeight()` inside the chip `Row`. Compose measures a
  `fillMaxHeight` child of a `Row` with `minHeight = maxHeight` equal to the Row's full
  available height, so the Row grew to full screen height and the `Box(weight = 1f)` holding
  the map got whatever was left — roughly 200 px. The divider now has a fixed height.

  Caught on device; unit tests did not and could not catch it, which is the map's known
  coverage gap. See the note at the end of 1.3.0.

## [1.3.0] — 2026-10-12

Map tab rework. No behavioural change to arrival prediction; `Eta.kt` and both feeds are
untouched.

### Fixed

- **OSM attribution was never rendered.** osmdroid 6.1.20 does not add a `CopyrightOverlay`
  itself — neither `MapView` nor `TilesOverlay` constructs one — and nothing in the app added
  it, so the map showed OpenStreetMap tiles with no attribution. It is a condition of tile
  use. Added, and re-added after every `overlays.clear()` so it does not vanish on the first
  frame.
- **Selecting a bus without location permission drew two cards on top of each other.** Both
  were pinned to `BottomCenter` at the same 16dp padding. They now stack in a `Column`.
- **The frame button did nothing when there was nothing to frame.** `fitTo` returned `false`
  silently. It is now disabled and dimmed.

### Changed

- **The map no longer auto-frames every bus on first load.** A one-shot `fitTo(vehicles)`
  spanned both networks, zooming out to a scale where no individual bus meant anything.
  Removed. The first open now centres on your location, or your saved stops, or the previous
  default if neither is available — via osmdroid's first-layout callback, so it never fights
  a deliberate pan or zoom.
- **Frame button is contextual.** With a line filter active it frames that line's buses;
  unfiltered it frames your saved stops, which is the more meaningful anchor. Saved stops are
  pinned to a fixed zoom rather than fitted, so a few nearby stops do not zoom in absurdly.
- **Filter chips show live vehicle counts** — `746 · 3` — and chips for lines with nothing
  running are dimmed to 45%. They were previously indistinguishable from a line with a dozen
  buses, which made an empty line look like a bug. A dimmed chip is still tappable: the route
  line is worth seeing even with no buses on it.
- **Bus line numbers only draw above zoom 14.** At the default zoom 12 with 60+ buses, a
  number on every dot was an unreadable wall of overlapping text. They always draw when a line
  filter is active or a bus is selected — the cases where the number is the point. Read from
  the projection per frame, so labels never lag a pinch.
- **Map controls are raised** (`shadowElevation = 3`) instead of a flat background, so they
  separate from route polylines of a similar colour.
- **A hairline divider separates the two providers' chips**, which previously read as one
  arbitrary list.
- Saved stops now persist their coordinates, backfilled from the stop catalog for entries
  stored before this. Needed for the frame button; also means the map can anchor on them.

### Tests

Still 34 passing. This change is UI-only, so there is nothing new to unit test beyond the
existing coverage — worth noting that the map has no test coverage at all, which is the main
gap here.

## [1.2.0] — 2026-10-12

Adds CARRIS line **746** from the same open GTFS feed as 716.

### Added

- **Line 746** (Marquês Pombal – Estação Damaia / Rossio – Sul e Sueste), bringing the
  CARRIS feed to 3 route_ids across 2 rider-facing lines, and the app to 6.
- Pattern **headsigns are now filled in**. `trip_headsign` is empty throughout the CARRIS feed,
  so the destination stop name is used instead — previously every 716 arrival showed a blank
  destination. This is what distinguishes the two 746 variants in the UI.
- Colours for the new variants in `ROUTE_COLORS`: `108_0` `#1F5FA9`, `108_3` `#0E7C7B`.
  CARRIS ships an empty `route_color` for both.
- `LineIdentityTest` covers the variant case (8 tests, up from 6).

### Changed

- **The map filter keys on short name, not route id.** 746 is two route_ids
  (`108_0` Marquês Pombal – Estação Damaia, `108_3` Rossio – Sul e Sueste). Filtering by
  route id produced two chips both labelled 746, and a tap showed only half the line.
  Filter chips are now deduped by short name and a tap shows every variant.
- Empty-state copy dedupes short names, which would otherwise have listed 746 twice.
- Bundled asset grew 24 KB → 77 KB (3 route_ids, 6 patterns, 81 stops, 2 854 shape points).

### Notes

- 746 is **not** structured like 716. 716 is a single `route_id` (`164_0`); 746 is two.
  In the feed as a whole, 72 of 113 lines have one `route_id` and 41 have two or more — 727
  has six. Any line added later needs **every** one of its route_ids listed in
  `CARRIS_TRACKED_LINE_IDS` or part of the line will silently never appear.
- `108_3` is lightly scheduled — 20 trips against 1 111 for `108_0`. It may legitimately be
  absent from the live feed for long stretches.

## [1.1.0] — 2026-10-12

Lisbon line **716** is back, served from CARRIS's own open GTFS feed alongside Carris
Metropolitana. No API keys, no tokens.

### Added

- **CARRIS as a second data provider.** The app now tracks 1707, 1717, 1720 and 1721 from
  `api.carrismetropolitana.pt/v2`, plus **716** from CARRIS's GTFS feed
  (`gateway.carris.pt/gateway/gtfs/api/v2.11`). Both feed the same prediction pipeline, so
  countdowns, map markers and route polylines work identically for either.
- `CarrisGtfs.kt` — loads the bundled GTFS asset and the live GTFS-RT feed into the existing
  `LineInfo` / `PatternInfo` / `PatternStop` / `Vehicle` models.
- `GtfsRt.kt` — hand-rolled protobuf reader for GTFS-RT `VehiclePosition`. Deliberately no
  new dependency: the release build minifies, so protobuf-javalite would have needed ProGuard
  rules, and only a handful of fields are actually read.
- `tools/build_carris_asset.py` — regenerates the bundled asset from the GTFS zip. The feed is
  a 47 MB archive whose `stop_times.txt` is 144 MB, which cannot be parsed on a phone, so the
  script distils just the tracked routes into a **24 KB** asset (`res/raw/carris_lines.json`)
  holding pattern stop sequences, cumulative km and shape geometry.
- The 49 stops on 716 are merged into the stop catalog, so they are searchable by name and
  offered by "paragem mais próxima".
- Tests: `GtfsRtTest` (10) builds protobuf messages byte by byte; `RealFeedTest` (6) runs a
  293-byte capture taken verbatim from the live CARRIS feed; `LineIdentityTest` (6) pins the
  namespacing contract. **32 tests, all passing.**

### Changed

- **Line and pattern ids are namespaced** as `cm:<id>` / `carris:<id>`. Two providers can emit
  the same raw id, and the pattern cache is keyed by pattern id — without this, an id collision
  would silently cross the two networks. `TRACKED_LINE_IDS` is now the union of
  `CM_TRACKED_LINE_IDS` and `CARRIS_TRACKED_LINE_IDS`.
- Provider-aware pattern and shape loading (`AppState.loadPattern` / `loadShapes` dispatch on
  the id prefix), so both networks contribute to ETA prediction and map overlays.
- The stop catalog cache on disk stays Carris Metropolitana only; the 49 CARRIS stops are
  merged in memory, which keeps the cache format and rebuild cost unchanged.
- User-facing strings resolve namespaced ids through `List<LineInfo>.shortNameOf`, so users see
  `716` and not `carris:164_0` — including the map dot labels and search-result badges.

### Fixed

- `GtfsRt.kt` would have mis-parsed every field after the first unknown one. `skip()` used
  `pos += readVarint()`, which captures the old `pos` *before* `readVarint()` mutates it as a
  side effect, landing one byte short and corrupting the stream.
- VehiclePosition field numbers are not sequential. Assumed `current_status=5`, `timestamp=6`,
  `stop_id=4`; CARRIS actually sends **4, 5 and 7**. `stop_id`, `current_status` and
  `timestamp` were all wrong; they are now read off a live capture, and `RealFeedTest` pins
  them so the assumption cannot silently return.

### Notes and limitations

- **CARRIS publishes no `tripupdates`** — only `vehiclepositions` (`tripupdates`,
  `stopupdates` and `alerts` all return 404). Arrivals for 716 are therefore *predicted* from
  vehicle positions, exactly as they already were for Carris Metropolitana. There is no
  server-side arrival estimate on this feed.
- The captured CARRIS `Position` messages carry only latitude and longitude — **no speed and no
  bearing**. `Eta` divides remaining kilometres by reported speed, so 716 predictions fall back
  to the `MIN_SPEED_KMH` floor of 12 km/h. Expect wider error bars than for Carris Metropolitana,
  where speed is reported.
- 716 runs weekdays and Saturdays only (`Inverno_Util`, `Inverno_Sabado`); there is no Sunday
  service, so the line is legitimately absent from the live feed on Sundays and overnight.
- The bundled asset is a snapshot, currently **feed version 20261011** (valid to 20261026).
  Re-run `tools/build_carris_asset.py` to refresh it. Timetable changes are not picked up until
  then; live positions always are.
- Line 716 is `route_id` `164_0` in the CARRIS feed. Route ids, not short names, are what the
  asset generator takes.

### Data sources

| | Carris Metropolitana | CARRIS |
|---|---|---|
| Static | `api.carrismetropolitana.pt/v2` | `gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS` |
| Live | `/vehicles` (JSON) | `/GTFS/realtime/vehiclepositions` (protobuf) |
| Auth | none | none |

Both are open and unauthenticated. CARRIS publishes its GTFS and GTFS-RT through its
developer portal; the [XTranPassengerAPI](https://github.com/joaodcp/Carris-API) that the
shipped CARRISway app uses for card loading is a different, private, undocumented API and is
not used here.

## [1.0.0]

Initial release: Carris Metropolitana lines 1707, 1717, 1720, 1721; collapsible stops screen
with live countdowns; OpenStreetMap view with labelled buses and per-line filter; stop search
and nearest-stop lookup.
