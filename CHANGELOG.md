# Changelog

All notable changes to this fork are documented here. The base of the tree is
upstream Look4Sat 4.4.9; entries below are relative to that tag.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [4.4.9-fork.1] — 2026-11-07

### Added

- **Pass analysis screen.** Full geometry for one pass (AOS, TCA, LOS, maximum
  elevation, azimuth at TCA, minimum range, peak range rate, azimuth sweep,
  sunlit fraction), orbital elements, elevation profile, Doppler plan, polar sky
  track and a sampling table. Opened from the chart icon on a pass row.
- **CSV and calendar export.** CSV follows RFC 4180 with UTC ISO 8601 timestamps
  and metadata in comment lines; `.ics` follows RFC 5545 with a `VALARM` lead
  time taken from the pass-alarm setting. The pass list can export every pass
  currently visible with one tap.
- **Saved observing sites.** Named station coordinates with a derived Maidenhead
  locator, switchable in one tap; switching moves the station position and the
  active site in a single write.
- **TLE import from text.** Pasted element sets go through the same parser and
  merge rules as file import, so a stale paste cannot overwrite fresher data.
- **Catalogue export.** CSV written with the importer's own column names, so the
  file can be imported back on another device.
- **Self-trained staleness model.** Power-law prediction of pass-time error
  against element age, refitted on device from every element-set replacement.
  Shown on the workbench and the analysis screen; drives the automatic refresh
  through a ten-second accuracy budget.
- **Workbench page.** Next-pass countdown, best passes in the next 24 hours,
  data freshness, window statistics (passes, visible time, satellites).
- **Themes.** Per-token colour overrides with JSON import and export.
- **Accessibility labels** for every icon button.

### Changed

- TLE parser scans for `1`/`2` pairs instead of chunking raw lines by three.
  One blank line used to shift every following block and silently drop the rest
  of the file.
- CSV reader honours RFC 4180 quoting. `"CSS, (TIANHE)"` used to shift every
  following column by one, corrupting the row without any error.
- Closest approach is refined by ternary search to ±100 ms and bracketed around
  the sampled minimum, so a two-day deep-space window cannot mislead it.
- Deep-space passes no longer count a ±24 hour placeholder window as 48 hours of
  visible time, and their AOS/LOS labels carry the date instead of showing the
  same clock time twice.
- Release builds use R8 full mode with aggressive renaming, resource shrinking,
  stripped logging and no Play dependency metadata block.

### Fixed

- The saved-sites dialog clipped its own content on a 1080×1920 screen.
- The Doppler table's TCA row used the nearest sample instead of the refined
  range rate (+84 Hz instead of +3 Hz), and transmit offsets were relative to
  the downlink nominal.
- The sampling table header claimed UTC while the app can display local time.
- The doppler table printed a receive offset computed from the wrong band for
  downlink-only transponders.
- Icon buttons were all announced as "button" by screen readers.
- Launcher icon and launch screen were cropped into a circle on API < 31.

### Removed

- Adaptive launcher icons and the SplashScreen compat library, replaced by
  density-specific rounded-square assets and a window background. On API < 31
  the compat library masks the icon to a circle regardless of the asset.
- Dead helpers with no call site (`DopplerPlan.peakDeviationHz`,
  `OrbitElements.minutesPerNodeCrossing`).

### Known limitations

- SGP4 against a precise ephemeris is unverified; no absolute-accuracy claim is
  made. See [docs/ACCURACY.md](docs/ACCURACY.md).
- A month-old element set cannot be corrected to ten seconds of pass-time
  accuracy, by any model. The app predicts that error and refreshes instead.
