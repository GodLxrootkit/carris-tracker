# Carris Tracker - Improvement Todo

## Done
- [x] Disable Android backup
- [x] Stop tracking release APK in git
- [x] Fix Robolectric activity resolution for Compose tests

## Near-term code fixes
- [x] Read persisted lat/lon in AppState.loadStops()
- [x] Fall back to poll time when CARRIS RT timestamp is 0
- [x] Log HTTP error body in Api.get()
- [x] distinctBy stop id in nearestStops()
- [x] Validate downloaded APK size before signer check
- [x] Adaptive polling when screen off / low battery

## Product improvements
- [ ] Direction-aware arrival filtering
- [x] Offline timetable cache for saved stops
- [x] "Bus approaching" notifications (basic)
- [x] Accessibility pass (content descriptions; text scale/colorblind-safe colors still open)
- [x] Battery/data optimizations for /vehicles polling (ETag + adaptive polling)

## Larger
- [ ] CP trains provider (separate module, optional map layer)
