# Offline strategy

## What's complete

The region-download system (`engine/RegionInstallStore.kt`, `RegionCatalogRepository.kt`,
`RegionDownloadManager.kt`, `RegionDownloadService.kt`, `tools/region-build/`) is real and
verified, not a design sketch. One region = one `.rpregion` archive (tiles + GraphHopper routing
graph + SQLite FTS4 search index), atomically installed (download → staging → checksummed →
renamed into place, so a killed process never leaves a half-written region visible). Published as
GitHub Release assets, listed in a `regions.json` catalog on this repo's own `main` branch — zero
new infrastructure, zero recurring cost.

Fully offline once installed: map rendering, routing, and search all read directly from the
installed region's files — none of the three make a network call for their core function. Verified
on a physical Pixel 6 Pro for Bremen, Baden-Württemberg, and Croatia (the last specifically to
prove the pipeline generalizes past Germany).

Settings' offline-region picker is a continent → country → region checkbox tree
(`SettingsActivity.kt`) with bulk "Download selected" — queued client-side since
`RegionDownloadService` only runs one download at a time.

## What's not offline, and why

Live traffic (`AutobahnTrafficRepository`/`TomTomTrafficRepository`), speed-camera feed refreshes,
road weather, and EV-charger/fuel-price lookups are all genuinely live data — offline caching them
would mean serving stale traffic/prices as if current, which is worse than clearly showing
"unavailable offline." Each already caches its last-known-good response for a bounded window (3
minutes for traffic, 6 hours for road signs, etc. — see `THIRD_PARTY_DATA.md`) and falls back to
that cache when offline, which is the right offline behavior for genuinely live data: stale-but-
labeled, not silently wrong.

Search only caches what's inside an installed region's `search.db` — there's no separate "recent
searches" or "cached POI details" cache beyond that, because no unified place model
(`SEARCH_ARCHITECTURE.md`'s `RSPlace`) exists yet to cache. Saved places / recent destinations
don't exist as a feature at all yet (see below) — nothing to make offline until they're built.

## Explicit gaps vs. the spec

- **Saved places / recents cached offline**: not applicable yet — `destination/
  SelectedDestinationStore` holds exactly one destination slot total, no favorites or history list
  exists to cache (see `MAP_AND_NAVIGATION_ARCHITECTURE.md`).
- **TomTom offline capability**: not investigated. Since TomTom is traffic-only in this
  architecture (routing/nav stays on GraphHopper, which is already fully offline — see
  `ZERO_COST_ARCHITECTURE.md`), there's no offline requirement to isolate here: live traffic is
  definitionally online-only, and the app already degrades correctly (cached-with-staleness-shown,
  never silently stale) when it isn't reachable.
- **Overture/OSM enrichment data as a downloadable region layer**: not started, blocked on Overture
  integration itself not existing yet (`DATA_SOURCES.md`).
