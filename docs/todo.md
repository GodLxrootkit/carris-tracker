# Carris Tracker - Review Findings

From the 2.9.18 Fertagus merge review. Assumed load: one phone polling a few saved
stops, plus the weekly CI refresh.

Status: findings 1-9 are done. Findings 10-13 are still open.

## Fix now

- [x] **1. CP and Fertagus trains run on the wrong days.**
  `tools/build_cp_asset.py` groups trips only by route and destination, combines
  every `arrival_time`, and writes `validOn: []`. `CpGtfs.scheduledArrivals`
  never checks `validOn`. Fertagus has weekday `DS` and weekend `FdS` trips, so
  Saturday shows weekday trains and weekdays show the weekend Coina trains.
  Holiday exceptions such as 2026-12-25 are ignored. 149 of 184 CP patterns mix
  service IDs.
  Fix: read `calendar.txt` and `calendar_dates.txt`, split by service date, and
  filter in `CpGtfs.scheduledArrivals` and `CpGtfs.pattern`.

- [x] **2. Stop-pattern variants are collapsed, so some CP trains disappear at
  intermediate stops.**
  `tools/build_cp_asset.py` uses `trs[0]` as the representative trip and one
  pattern per route plus destination. Stops only present in another variant are
  dropped. CP route `21-94_2006-94_30007` to "Lisboa Santa Apolonia" has a
  6-stop and a 10-stop variant; the 10-stop trips lose Espinho, Pombal,
  Entroncamento and Santarem. 44 CP patterns have more than one stop sequence.
  Fix: group by route, headsign and stop sequence (or Fertagus `pattern_id`),
  emit one pattern per sequence, and keep per-variant times.

- [x] **3. Late-night trains from the previous service day are missed or shown a
  day late.**
  `CpGtfs.scheduledArrivals` calculates only from today's midnight. Fertagus has
  105 stop times at 24:00 or later; trip 3108 arrives Roma-Areeiro at 24:06. At
  00:02 on Tuesday that Monday train arrives in four minutes, but the code maps
  24:06 to Wednesday 00:06. With service-day filtering it would be dropped.
  Fix: also check the previous service day for times at or after 1440, subtract
  1440, and apply that day's validity.

- [x] **9. Splitting CP variants duplicated one departure at a shared stop.**
  Route `24-94_9001-94_10009` to Regua has an express and an all-stops pattern
  departing stop `94_9001` at the same minute on the same days. After finding 2
  split them, both reached `CpGtfs.scheduledArrivals` and the timetable fallback,
  so the stop showed two identical rows at 23:40. Fix: stop feeding CP patterns
  through `Timetable.arrivalsFromPatterns` (CpGtfs already supplies them) and
  dedupe CP arrivals by line, headsign and scheduled second.

## Fix later

- [x] **4. Existing saved CP stops keep hiding Fertagus.**
  `AppState.upcoming()` filters arrivals by tracked lines, but only Metro has a
  one-shot repair for lines added after a stop was saved. A user who saved
  Roma-Areeiro before 2.9.18 still tracks CP lines only. Add a one-shot CP repair
  that adds `cp:fert_*` from `CpGtfs.stops()`.

- [x] **5. The older test still hardcodes 2026-11-11.**
  Weekday/Saturday, CP-variant and after-midnight coverage now lives in
  `CpFertagusAssetTest`, with dates taken from the asset's validity fields.

- [x] **6. CP and Fertagus pattern builders are duplicate copies.**
  Both copies had the same calendar and variant bugs. Extract one helper taking
  trips, stop times, stops, a stop-ID map and an ID prefix. About 20 lines can go.

- [x] **7. `CpGtfs` scans every pattern's stop list for nothing.**
  After `perPattern.has(pid)`, the code loops through the pattern's stops. The
  times file is built only from those stops, so the loop is redundant. Delete it.

- [x] **8. Two CP routes display raw route IDs as their name.**
  Routes `24-71_37606-94_34009` and the reverse show the raw ID because the
  Badajoz stop `71_37606` is not in the CP stops feed. The builder now falls back
  to "Entroncamento - Badajoz".

## Still open

- [ ] **10. No test covers the one-shot Fertagus migration.**
  `repairSavedFertagusLines()` can silently do nothing if the saved-stop network
  or catalog match changes. Add a test that starts with a saved CP stop and no
  `cp:fert_*` lines, runs the backfill, and asserts the lines are added and the
  one-shot flag is set.

- [ ] **11. CP and Timetable duplicate the validity predicate.**
  `Timetable.PatternInfo.runsOn` and `CpGtfs.runsOn` implement the same calendar
  rules separately. Extract one shared function so a future exception fix cannot
  make CP and the timetable fallback disagree.

- [ ] **12. `calendar_dates`-only services would run every day.**
  If a feed service has no `calendar.txt` row and only added dates, empty
  `validFrom`, `validTo` and `validDays` currently mean "always". No current feed
  uses this, but store the added dates in a field that cannot mean "always" before
  one appears.

- [ ] **13. The one-shot repair can override a deliberate choice.**
  A user upgrading from 2.9.18 who deliberately deselected Fertagus lines will
  have them re-added once. Either accept and document this, or gate the repair on
  a version marker rather than the preference alone.
