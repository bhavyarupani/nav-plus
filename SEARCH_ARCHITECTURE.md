# Search architecture

## Today

`engine/OfflineSearchEngine.kt` implements the `SearchEngine` interface (`engine/
NavigationEngine.kt`) against every installed region's `search.db` (SQLite FTS4, built at region-
package time from OSM node data — see `OFFLINE_STRATEGY.md`). One source (OSM), so results are
returned directly as `SearchResult(title, subtitle, coordinate)` per FTS row, merged across
installed regions and sorted by distance from the query point. No deduplication/conflation logic
exists anywhere in the repo — confirmed by a full-repo search for "conflat"/"dedup" near place
code, which found nothing beyond an unrelated comment about *visual* layering, not data merging.

Node-based only: bare street names with no house number don't resolve (would need way-centroid
geometry, not built). Ranking is distance-only — no text-relevance scoring, brand/category
weighting, or route-deviation-aware ranking for in-navigation searches exists yet.

## Where this needs to go

The spec's target is a unified `RSPlace` model — one merged record per real-world place instead of
separate hits per source (TomTom/Overture/OSM shown as one place with opening hours from OSM,
brand/phone from Overture, address from whichever source has it). None of the merge/conflation
layer exists yet: no `RSPlace` type, no matching logic (geographic distance + name similarity +
brand/phone/website signals), no confidence scoring.

This is intentionally not started before the prerequisite pieces land: TomTom's Traffic API access
is confirmed free, but TomTom's *Search/Places* API terms haven't been checked (may be a separate
product with separate free-tier limits); Overture Maps integration is unresearched (see
`DATA_SOURCES.md`). Building `RSPlace` conflation against only one real source (OSM) today would
produce untested merge logic with nothing to actually merge against — the interface is worth
designing once there's a second source confirmed reachable for free.

## Design intent (not yet implemented)

```
RSPlace(
    id, name, alternativeNames, latitude, longitude,
    category, subCategory, brand,
    street, houseNumber, postcode, city, country,
    phone, website,
    openingHours, isOpenNow,
    parkingAvailable, parkingType, parkingEntrance,
    wheelchairAccessible, toiletsAvailable,
    fuelTypes, fuelPrices,
    evChargingAvailable, chargingConnectors, chargingPower,
    heightRestriction,
    sourceTomTom, sourceOSM, sourceOverture, confidenceScore,
    navigationEntranceLatitude, navigationEntranceLongitude,
    lastUpdated,
)
```

`SearchEngine`'s existing interface boundary (`engine/NavigationEngine.kt`) already isolates the
UI from the raw per-source schema, so swapping `OfflineSearchEngine`'s single-source implementation
for a multi-source-conflating one later shouldn't require touching `MainActivity`/
`NavigationActivity` call sites — that boundary was the point of introducing the interface in the
first place (see `ZERO_COST_ARCHITECTURE.md`).

## Ranking for in-route search (not started)

The spec asks for route-deviation-aware ranking (closest to route + smallest detour + easy
re-entry, not straight-line distance) for searches made mid-navigation — e.g. "petrol" while
driving. No such mode exists; `RouteStopOptimizer` (`stops/RouteStopOptimizer.kt`) does compute
detour cost for its own automatic stop-suggestion feature (supermarket/fuel search along a route),
which is the closest existing building block — a manual in-route search feature could likely reuse
its detour-cost math rather than starting from scratch.
