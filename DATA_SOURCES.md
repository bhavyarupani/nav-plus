# Data sources

Per-source licensing, attribution, caching, and cost detail lives in `THIRD_PARTY_DATA.md` — this
file is a short index, not a duplicate, so there's exactly one place that detail can drift out of
date.

| Domain | Current source | Status |
|---|---|---|
| Map tiles | Geofabrik extracts → Planetiler (OpenMapTiles schema) | Complete, per-region via `tools/region-build/` |
| Routing graph | Same Geofabrik extracts → GraphHopper CH import | Complete |
| Search index | Same extracts → custom SQLite FTS4 build | Complete, node-based only (see `SEARCH_ARCHITECTURE.md`) |
| Live traffic (Germany) | Autobahn GmbH API | Complete, named-Autobahn-ref only |
| Live traffic (elsewhere) | TomTom Traffic API | Complete, bbox-based, free tier, no key configured yet |
| Speed cameras | Open-GATSO, official government feeds (FR/BE/LU), OSM | Complete |
| Road signs/signals/limits | OpenStreetMap (Overpass) | Complete |
| Road weather | DWD (Deutscher Wetterdienst) | Complete, Germany-only |
| Terrain/elevation | Open-Meteo / Copernicus DEM | Complete, global |
| Fuel prices | Tankerkoenig | Complete, Germany-only, requires API key |
| EV charging | Open Charge Map | Complete, global, requires API key |
| POI enrichment (brand/website/phone/hours) | — | Not started — see "Overture Maps" below |

## Provider abstraction

`engine/NavigationEngine.kt` defines `RoutingEngine`/`GuidanceEngine`/`SearchEngine`/
`MapDataProvider` interfaces, all implemented and consumed by all three screens. There is
deliberately **no** shared `TrafficProvider` interface yet — `AutobahnTrafficRepository` and
`TomTomTrafficRepository` are concrete, independently-called classes merged ad hoc in
`MainActivity`/`RoadPulseNavigationScreen`. Worth introducing once a third traffic source is added;
not done preemptively for two sources.

## Overture Maps — not started

Evaluated only at the concept level so far (see `THIRD_PARTY_DATA.md`'s "Sources not integrated").
No code, no schema mapping, no license/update-cadence research has been done. Before starting:
confirm Overture's release format and update frequency, confirm no billing account is required for
the intended usage pattern (bulk download vs. API), and scope what fields RoadPulse actually needs
(brand, website, phone, category — see `SEARCH_ARCHITECTURE.md`'s `RSPlace` sketch) against what a
real Overture release actually contains for the target countries.
