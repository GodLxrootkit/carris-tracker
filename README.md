# Carris Tracker

> **This is a hobby project.** It is completely vibe coded, has no formal review, and is not
> affiliated with or supported by Carris Metropolitana or CARRIS. Use it at your own risk.

An Android app for waiting for buses in and around Lisbon.

It answers two questions: **when is the next bus due at the stops I use**, and **where are the
buses on my lines right now**. Two networks in one app — **Carris Metropolitana** (the network
outside Lisbon) and **CARRIS** (Lisbon municipality).

The interface is in Portuguese.

## What it does

### Paragens — your stops

- Save the stops you use, found by name search or by proximity to you.
- Each stop lists the next passages: line number, destination and a countdown.
- Countdowns are worked out from **where the bus actually is**, not only from the printed
  timetable, so a bus stuck in traffic shows the real delay rather than the optimistic one.
- A **red** countdown means it is predicted from a live vehicle. A **plain time** means timetable
  only.
- Collapse a card to see a one-line summary, or long-press to reorder your stops.

### Mapa — the live map

- Every bus on the lines you follow, drawn as a dot in its line's colour with the number printed
  on top. Tap one to see where it terminates.
- The route of each line drawn as a coloured line, plus your own position.
- A per-line filter, and a button to frame all the buses at once.
- Positions refresh every 9 seconds for Carris Metropolitana and every 15 seconds for CARRIS,
  each matched to how often the operator actually republishes.

### Definições — settings

- **Choose which lines to follow.** All 717 Carris Metropolitana lines and all 176 CARRIS routes
  are listed together and searchable.
- Search ignores accents, so typing `alcacer` or `algacs` still finds `Alcácer` or `Algács`.
- Your choice is remembered between launches. The map, the arrivals and the filters all follow it.
- A few routes appear greyed out with a reason: the operator's own distance data for them is wrong,
  so any prediction based on it would be a lie.
- **In-app updates.** Optionally checks GitHub for a newer build when the app opens, and offers it
  for you to download and install. Nothing is downloaded or installed without you saying so, and a
  build that is not signed with our key is deleted rather than offered.

## Privacy

- No account, no sign-in, no analytics, no advertising, nothing sent anywhere.
- **No API keys or tokens are needed or stored.** Both operators publish public feeds and the app
  reads them directly.
- Your saved stops and your line choices are kept on your device and never leave it.

## Installing

Grab `carris-tracker-release.apk` from the
[releases page](https://github.com/GodLxrootkit/carris-tracker/releases) and open it. Android
will ask you once to allow installs from that source.

Requires Android 7.0 (API 24) or newer. Location is used only to find nearby stops and to show
where you are; the map and the arrival times work without it.

## Screenshots

Not yet captured. To add some, drop PNGs in `docs/screenshots/` and reference them here:

```markdown
![Paragens](docs/screenshots/paragens.png)
```

## Where the data comes from

| | Carris Metropolitana | CARRIS |
|---|---|---|
| Area | the network outside Lisbon | Lisbon municipality |
| Lines | all 717 | all 176 |
| Schedules and routes | public API | GTFS feed |
| Live positions | public API | GTFS-RT feed |
| API key required | none | none |

Map tiles © OpenStreetMap contributors.

This is an independent, unofficial app.

## Building it

See **[BUILD.md](BUILD.md)** for the short version:

```bash
./build.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Signing a release needs your own keystore; instructions are in BUILD.md. **Never commit a keystore
or its passwords** — this repository's are gitignored, and the ones behind the published APKs are
not in it.

### Releases

Two workflows handle releases, and neither of them can see the signing key as a file:

- **`refresh-carris-asset.yml`** runs daily and regenerates the bundled CARRIS data, committing
  only when the feed has actually changed.
- **`weekly-release.yml`** runs Sundays at 12:00 UTC, bumps `versionName` by the minimum increment
  and `versionCode` with it, builds a signed APK, checks the APK's certificate against the
  fingerprint compiled into the app, and publishes it as a release. That is the step that actually
  delivers the weekly data refresh to phones.

The key reaches the runner as repository secrets and is materialised into `build/`, which is
gitignored, then deleted. Before publishing, the workflow refuses to continue if the built APK is
not signed with the expected certificate — the same check the in-app updater performs on the
device.

## More

- **[CHANGELOG.md](CHANGELOG.md)** — what changed in each release.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — how it predicts arrivals, and the awkward
  parts of the two data sources.

## Licence

MIT — see **[LICENSE](LICENSE)**. Use it, fork it, ship it commercially, change it. The only
requirement is keeping the copyright notice.
