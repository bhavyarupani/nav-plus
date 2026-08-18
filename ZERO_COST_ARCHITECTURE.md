# Zero-cost architecture

This document records the free-stack replacement for the Google Maps/Navigation SDK
implementation, per the explicit instruction to eliminate all recurring provider costs. It is
the authoritative decision record for that migration — update it whenever a component choice
changes.

**Status as of this document's creation:** research and foundation phase. The component choices
below are decided and justified with sources. Full working implementations of routing and search
are **not yet built** — see "Implementation status" at the end of this document for exactly what
exists versus what remains. Do not read this document as a claim that the migration is complete.

## Component table

| Component | Selected library/data source | Licence | Runtime location | Internet required | Offline supported | Billing account required | Usage quota | Recurring provider cost | Attribution requirement | Known limitation |
|---|---|---|---|---|---|---|---|---|---|---|
| Map rendering | MapLibre Native Android (`org.maplibre.gl:android-sdk`) | BSD-2-Clause | On-device | No (once style/tiles are local) | Yes | No | None | €0 | MapLibre name not legally required, but OSM data attribution is (see below) | Renderer only — supplies no map data, routing, or search of its own |
| Map data source | Geofabrik regional extracts (`download.geofabrik.de`), per German Bundesland | Data: ODbL 1.0. Geofabrik's extraction service itself is free, no account | Downloaded at build/update time, not at app runtime | Yes, once per extract/update | Yes (extract is a static file) | No | None documented; Geofabrik asks that automated/bulk mirroring be reasonable, not that individual regional downloads be restricted | €0 | Must credit "© OpenStreetMap contributors" and link openstreetmap.org/copyright; ODbL share-alike applies to the *database*, not to the app's own original code | Extracts are updated daily upstream; this app's bundled/downloaded packages will lag behind by however long between our own regenerations |
| Vector tile generation | Planetiler (Java, single jar) with the OpenMapTiles schema | Planetiler: Apache 2.0. OpenMapTiles *cartography/schema*: CC BY 4.0 | Run on our own build machine, not on-device, not on any hosted service | Only to fetch the source extract | Output (MBTiles/PMTiles) ships as a static file | No | None (local tool) | €0 | OpenMapTiles schema requires attribution of OpenMapTiles when its schema is used (separate from the OSM data attribution) | Tile generation is a build-time step we run and re-run manually/on a schedule — not a live service |
| Routing / turn-by-turn | GraphHopper core library, **pinned to 7.0** (`com.graphhopper:graphhopper-core:7.0`), used as an embedded Java library with our own Android integration layer | Apache 2.0 | On-device, embedded routing graph built from the same Geofabrik extract | No, once the routing graph is built and stored on-device | Yes | No | None | €0 | Apache 2.0 requires preserving the licence/copyright notice; no attribution UI requirement | Real route calculation confirmed working on the physical Pixel 6 Pro — see "Routing engine decision" below for the three real Android/ART incompatibilities found and fixed along the way |
| Search / geocoding | Custom offline index (25,930 named OSM nodes) built from the same Bremen extract, stored in Android's built-in SQLite with FTS4 (no extra runtime dependency — `android.database.sqlite` is a platform API) | Our own code; OSM data still ODbL | On-device | No | Yes | No | None | €0 | Same OSM attribution as map data | Node-based only (POIs, `place=*`, and `addr:housenumber`+`addr:street` points) — road/street-name search would need way-centroid resolution, not built; see "Implementation status" |
| Voice guidance | Android `TextToSpeech` (platform API) | Platform API, no separate licence | On-device | No | Yes | No | None | €0 | None | Quality/voice availability depends on what TTS engine the user has installed; must degrade to text-only if none is available, as instructed — confirmed: `VoiceGuidance` checks `TextToSpeech`'s init callback and silently no-ops if it fails |
| Android Auto | `androidx.car.app` (already integrated) | Apache 2.0 | On-device | No | Yes | No | None | €0 | None | Unchanged by this migration — car-app-library only needs `RoutingInfo`/`Step`/`Lane` data from whatever engine produces it |

Every row above: billing account required = **No**. Recurring provider cost = **€0**. Paid quota
dependency = **None**.

## Routing engine decision: why not Valhalla or OSRM first

The instruction named Valhalla as the primary candidate, to be embedded and executed on-device.
That was evaluated first, honestly, against this actual machine and this actual timeline:

- Valhalla and OSRM are both C++ engines. Getting either running on Android requires the Android
  NDK plus a full native cross-compile of the engine and its dependency chain (for Valhalla:
  protobuf, zlib, libcurl, sqlite, geos, and more), then a hand-written JNI binding layer — the
  Valhalla project explicitly does not ship one; a real community mobile-build project
  ([Rallista/valhalla-mobile](https://github.com/Rallista/valhalla-mobile)) confirms this is
  possible but is itself a nontrivial standalone build project.
- This development machine currently has **no Android NDK, no CMake, and no Ninja installed**
  (checked directly, not assumed). Installing and correctly configuring all three, then
  cross-compiling Valhalla's full dependency chain for at least `arm64-v8a`, is realistically a
  multi-day effort on its own — before any app integration work starts. Attempting it inside this
  session and presenting a partial/untested result would not meet the bar of "don't claim
  completion of a nonfunctional feature."
- OSRM has the identical native-toolchain problem, so it does not change this conclusion.
- BRouter was excluded outright: it is **AGPLv3-licensed**, which the migration instructions
  explicitly require flagging rather than silently adopting into a proprietary app.
- GraphHopper is a **pure Java/JVM library** (Apache 2.0). It needs no NDK, no native
  cross-compilation, and no JNI layer — it runs on Android exactly as any other JVM library does,
  because Android's ART runtime executes ordinary JVM bytecode. Its official Android demo was
  removed after GraphHopper 1.0 ("offline routing is no longer officially supported but should
  still work," per the project itself), which is a real maintenance-risk data point, not a
  fabricated one — but it does not carry the same up-front native-build risk as Valhalla/OSRM. A
  third-party "Android fork" of GraphHopper was found and checked directly: it has 0 stars and 2
  commits, i.e. not something to depend on. The plan instead is to depend on the **official**
  `graphhopper-core` artifact and write our own thin Android integration layer (file paths,
  background-thread graph loading, lifecycle handling).

**Conclusion: GraphHopper core is the practical first on-device routing engine for this project.**
Valhalla remains the better long-term candidate on routing quality/features and stays documented
as a future upgrade path once NDK build tooling is set up as its own project, not bundled into
this migration.

### Three real GraphHopper/Android incompatibilities found and fixed

Getting GraphHopper actually working on-device took three separate rounds of real, on-device (or
`javap`-confirmed) failures — each is recorded here so the reasoning isn't lost:

1. **`RAMDataAccess` / `VarHandle.withInvokeExactBehavior()` — D8 dexing failure, then a runtime
   `NoSuchMethodError`.** GraphHopper's default graph storage class calls
   `VarHandle.withInvokeExactBehavior()`. D8 refuses to dex this below `--min-api 26`
   (`minSdk` was bumped from 24 to 26 for this reason — see `app/build.gradle.kts`). Bumping
   `minSdk` alone was not enough: even at API 26+, Android's ART `core-oj.jar` does not implement
   that method at all, producing a real on-device `NoSuchMethodError`. Confirmed via `javap -c`
   that `MMapDataAccess` does not call this method (0 occurrences, vs. 2 in `RAMDataAccess`) in
   GraphHopper 9.1 and 10.2.
2. **`CustomModel` / Janino runtime bytecode compilation — unsupported on ART.** GraphHopper 8.x
   through (at least) 10.2 compiles routing-weight expressions (the `CustomModel` JSON: `"if":
   "true", "limit_to": "car_average_speed"`, etc.) to bytecode at runtime using Janino. On a real
   device this threw `IllegalArgumentException: ... Cannot compile expression ... class
   "CustomWeightingHelper" could not be found` — Janino's runtime class generation is incompatible
   with Android's ART/DEX class loading, not fixable by configuration. **Fix: pinned
   `graphhopper-core` to 7.0**, which predates the `CustomModel`/Janino system and instead uses
   the older, simpler `Profile.setVehicle("car").setWeighting("fastest")` API backed by
   precompiled Java classes (e.g. `FastestWeighting`). Verified via `javap` that neither
   `RAMDataAccess` nor `MMapDataAccess` in 7.0 call `withInvokeExactBehavior()` either (0
   occurrences in both), so issue #1 above does not resurface at 7.0 — no MMAP storage override
   was needed.
3. **`javax.lang.model.SourceVersion` — a JDK `java.compiler`-module class Android does not ship
   at all.** Even after pinning to 7.0, the very first real route calculation on-device failed
   with `NoClassDefFoundError: Ljavax/lang/model/SourceVersion;`. Root cause, confirmed via
   `javap -c` bytecode inspection of `IntEncodedValueImpl.isValidEncodedValue`: GraphHopper 7.0
   validates every encoded-value name (`car_access`, `car_average_speed`, etc. — used
   unconditionally by every profile, not an edge case) by calling
   `javax.lang.model.SourceVersion.isKeyword(CharSequence)`, a class that lives in the JDK's
   `java.compiler` module. Android does not include this module at all — this is a genuine,
   distinct incompatibility from #1 and #2, not a variant of either. **Fix: a minimal
   compatibility shim** at
   `app/src/main/kotlin/javax/lang/model/SourceVersion.kt` implementing only the one method
   GraphHopper calls, backed by the fixed, publicly-documented list of ~50 Java reserved words and
   literals from the Java Language Specification (a fact, not copyrightable expression — no JDK
   implementation source was copied). Classes may be defined under `javax.*` package prefixes from
   application code on Android (unlike the JVM-reserved `java.*` prefix), which is why this
   approach works.

With all three fixed, a real route calculation (Bremen Hauptbahnhof → Bremen Airport) succeeded on
the physical Pixel 6 Pro: **distance 5224 m, duration 466 s, 71 geometry points** — matching the
JVM-only test run on the same coordinates and graph (5224.511 m / 466549 ms) almost exactly (the
small integer-truncation difference is `Int` rounding in `Route.distanceMeters`/`durationSeconds`,
not a routing discrepancy).

## Map-package strategy

Per-Bundesland packages from Geofabrik, generated through Planetiler into MBTiles, matching the
suggested region list (Baden-Württemberg, Bavaria, Hesse, North Rhine-Westphalia, remaining
states, optional all-of-Germany bundle). Estimated download/installed sizes for the vector-tile
packages and the routing graphs will be measured and reported here once the first real package is
generated — no size numbers are stated yet because none have been measured; publishing guessed
figures would violate the "never invent" standard already applied throughout this project's data
handling.

## Migration approach

Following the specified 15-step sequence. Completed so far:

1. ✅ Feature branch `feature/free-stack-migration` created off a committed baseline of the
   working Google implementation (nothing destructive has happened; `main` is untouched).
2. ✅ Baseline recorded (initial commit `8bc6f68`).
3. ⏳ Interfaces for map rendering, routing, search, and guidance — in progress, see
   `app/src/main/kotlin/com/roadpulse/auto/engine/` once added.
4–15. Not started yet.

## Implementation status

**Built and verified real, end to end:**
- A genuine Bremen extract downloaded from Geofabrik (21MB `.osm.pbf`, real German OSM data, not
  a fixture).
- A real vector tile package generated from it with Planetiler + the OpenMapTiles schema: 707
  tiles, 1,012,755 features, 12.2MB `.mbtiles`, zoom 0–14. Bremen was chosen deliberately as the
  smallest complete German Bundesland (Geofabrik's own listed sizes), to prove the full pipeline
  fast rather than starting with a multi-GB extract.
- `LocalMbtilesServer`: a loopback-only HTTP/1.1 server (Android has no `HttpServer` class to
  reuse) that reads tiles directly from that `.mbtiles` SQLite file and serves them to MapLibre.
  Verified correct at the protocol level with a direct `nc` request against the running server —
  real `200 OK`, real tile bytes — independent of whether MapLibre itself was involved.
- `MapLibrePocActivity`: MapLibre Native confirmed initializing and rendering on the physical
  Pixel 6 Pro (attribution control visible, no crash), successfully fetching *some* tiles from
  the local server over real HTTP.
- Two real, non-obvious bugs found and fixed along the way: (1) `InetAddress.getLoopbackAddress()`
  resolved to IPv6 `::1` on this device while both the style JSON and MapLibre's client target
  IPv4, silently binding the server somewhere nothing would ever connect to; (2) Android blocks
  cleartext HTTP by default even to loopback, requiring an explicit, narrowly-scoped
  `network_security_config.xml` permitting cleartext to `127.0.0.1` only.

**Resolved.** The tile-cancellation issue above was root-caused: the style's two `symbol` layers
(`water-name`, `place-labels`) used `text-field` with no `glyphs` URL defined anywhere in the
style. Removing those two layers immediately fixed rendering - real roads, water, landcover, and
motorways now paint correctly on screen, confirmed on the physical Pixel 6 Pro (Bremen's actual
road network and the Weser river are visibly correct). The apparent mechanism: MapLibre appears
to process a style's layers as a single pipeline per tile, and a symbol layer that can't resolve
its glyphs dependency stalls that pipeline for the whole tile, not just the symbol layer - which
surfaced as every layer's tiles getting silently cancelled, not an error tied to the text layers
specifically. This cost a large amount of debugging time before being found; the lesson for next
time is to bisect the style (delete layers by half) before chasing server/network/timing theories.

Labels are deferred, not abandoned: reintroducing them needs a real `glyphs` PBF source, which
under the zero-cost constraint means generating font glyph ranges locally (there are open-source
tools for this) rather than pointing at any hosted glyphs service - tracked as follow-up work, not
blocking the rest of the migration.

**Resolved.** The routing pipeline is now built and verified real, end to end:
- A GraphHopper routing graph (contraction-hierarchy prepared, `car` profile) built from the same
  Bremen `.osm.pbf` extract used for the map tiles.
- `GraphHopperRoutingEngine`, this project's `RoutingEngine` implementation
  (`app/src/main/kotlin/com/roadpulse/auto/engine/GraphHopperRoutingEngine.kt`), loading that
  bundled graph from app assets into internal storage and calculating real routes via GraphHopper
  7.0's simple named-weighting API.
- Three separate, real Android/ART incompatibilities found and fixed along the way — see "Three
  real GraphHopper/Android incompatibilities found and fixed" above.
- `GraphHopperPocActivity` confirmed a real on-device route calculation on the physical Pixel 6
  Pro: Bremen Hauptbahnhof → Bremen Airport, 5224 m / 466 s / 71 geometry points, matching the
  JVM-only pre-verification run.

**Resolved.** The real-time navigation engine, voice guidance, map annotation layer, base style,
and offline search are all built and verified:
- `GraphHopperGuidanceEngine` (`RoutingEngine`+`GuidanceEngine`) replaces Google Navigator's
  real-time behaviour entirely with on-device geometry: cross-track/along-track projection of
  each GPS fix onto the active route (the same proven equirectangular-projection approach as
  `driving/JunctionPriorityGuidance.kt`) drives current/next maneuver step, remaining distance,
  proportional ETA, off-route detection with a confirmation window, and automatic recalculation.
  Unit-tested (4 tests) since it's pure geometry with an injectable clock.
- `VoiceGuidance`/`VoiceGuidancePlanner` replace `Navigator.setAudioGuidance` with Android's
  `TextToSpeech` - phrase selection is pure, unit-tested logic (9 tests) separated from the TTS
  engine itself, degrading silently to a no-op if no TTS engine is installed.
- `MapLibreMapController` wraps MapLibre's `SymbolManager`/`LineManager`/`MapLibreMap`
  (`android-plugin-annotation-v9` 3.0.2) behind the same marker/polyline/camera shape
  `MainActivity`/`NavigationActivity`/`RoadPulseNavigationScreen` already use against
  `GoogleMap`/`Marker`/`Polyline`. Verified on the physical Pixel 6 Pro: a marker and polyline
  added through the controller render correctly over the real Bremen tile pipeline (confirmed by
  screenshot).
- `maplibre_style_day.json`/`maplibre_style_night.json` replace `RoadPulseMapTheme`'s Google
  `MapStyleOptions` JSON with a from-scratch MapLibre GL style rewrite, hand-matched color-by-color
  to the existing day/night palette. Verified both themes render correctly on-device in each
  Android dark-mode state (confirmed by screenshot). Still has no label/symbol layers, for the
  same glyphs reason as above.
- `OfflineSearchEngine` replaces Google Places autocomplete + `FetchPlaceRequest` with a SQLite
  FTS4 index of named OSM nodes - POIs, `place=*` entries, and `addr:housenumber`+`addr:street`
  points - built from the same Bremen extract by a dev-machine-only tool (`BuildSearchIndex.java`,
  using GraphHopper's own bundled `PbfReader`/`Sink` OSM-PBF parser, already a transitive
  dependency, plus `org.xerial:sqlite-jdbc` at build time only, not shipped in the app). Real run:
  scanned 1,661,904 OSM elements, indexed 25,930 named places into a 3.1MB SQLite file bundled as
  an asset. Verified on-device: a real search for "Hauptbahnhof" against the installed app
  returned 20 correctly distance-sorted results. Query construction (`OfflineSearchQueryBuilder`)
  is pure and unit-tested (6 tests), stripping all but Unicode letters/digits per token before
  appending an FTS4 prefix wildcard, so raw user input can never be interpreted as FTS query
  syntax. Known limitation: node-based only - road/street-name search without a house number would
  need way-centroid resolution, not built in this pass.

**Resolved for `MainActivity`.** The app's home/"browse" screen now runs entirely on the free
stack - the first of the three Google-based screens to be migrated:
- `SupportNavigationFragment`/`GoogleMap` replaced by a raw MapLibre `MapView` + `LocalMbtilesServer`
  (the same Bremen tile pipeline used throughout this migration) + `MapLibreMapController`.
- Every marker/polyline layer (destination pin, speed-camera markers with clustering, road
  infrastructure signs, Autobahn traffic events, road facilities including OpenChargeMap chargers,
  weather warnings, DWD road-surface forecast, and speed-limit-coloured road sections) ported to
  `MapLibreMapController`'s marker/polyline API - all the OSM/Autobahn/Tankerkoenig/OpenChargeMap
  data-fetching logic underneath is completely unchanged, since none of it was ever Google-specific.
- `MapMarkerIconFactory` changed to return `(iconId, Bitmap)` instead of Google's `BitmapDescriptor`;
  `NavigationActivity`/`RoadPulseNavigationScreen` (not yet migrated) keep working via a one-line
  `BitmapDescriptorFactory.fromBitmap(...)` wrap at their call sites.
- Google Places autocomplete replaced by an in-app search dialog backed by `OfflineSearchEngine`,
  live-querying as the user types.
- `SelectedDestination.placeId` is synthesized (`"osm:<lat>,<lon>"`) for offline-search-sourced
  destinations, since they have no Google place ID; `NavigationActivity`/`RoadPulseNavigationScreen`
  were given a small, targeted fix (not their full migration - that's still pending) to build their
  Google `Waypoint` from latitude/longitude when available rather than requiring a real place ID,
  so destinations picked via the new offline search still route correctly through the still-Google
  navigation screens in the meantime.
- Per-marker tap-to-see-details (Google's info-window behaviour) was deliberately dropped in this
  pass - MapLibre's `SymbolManager` has no built-in equivalent, and building a custom callout UI
  was out of scope here; the marker icons themselves already encode the key information visually,
  and the status text below the map summarizes counts/categories.
- Verified end to end on the physical Pixel 6 Pro, not just compiled: a real launch of the actual
  `MainActivity` (the app's launcher activity) rendered the MapLibre map with zero crashes, and the
  real data pipeline populated real markers and polylines - confirmed by screenshot showing "19
  cameras · GATSO + OSM", "10 limit signs · 1775 coloured speed sections · 21 safety points · 13
  signals", with camera/traffic-signal/speed-limit icons and coloured speed-section polylines
  actually visible on screen.

**Resolved for `NavigationActivity`.** The live turn-by-turn driving screen now runs entirely on
the free stack - Google Navigation SDK's `Navigator`, `SupportNavigationFragment`, and its
built-in speedometer/speed-limit-icon/route-line/puck rendering are gone from this file
completely:
- `GraphHopperRoutingEngine` + `GraphHopperGuidanceEngine` replace `Navigator` for both route
  calculation and real-time guidance (map-matching, ETA, off-route detection/rerouting).
- `VoiceGuidance` replaces `Navigator.setAudioGuidance` for spoken turn-by-turn prompts.
- A raw MapLibre `MapView` + `LocalMbtilesServer` + `MapLibreMapController` replace
  `SupportNavigationFragment`/`GoogleMap` - route-line and location-puck rendering had no
  existing app code to port (Google's fragment drew both internally), so this is genuinely new
  code, not a port. Turn-arrow rendering on the map itself is out of scope for this pass
  (documented gap, not a silent omission) - the route line, voice prompts, and the road-ahead/
  signboard panels carry maneuver information instead.
- GPS updates are now owned directly by this activity via `android.location.LocationManager`
  (`Navigator` previously did this internally), feeding every fix to the guidance engine, the
  map's location puck, and the camera.
- `RouteStopOptimizer` gained a `RoutingEngine`-based `setRoute` overload (see below) for the
  supermarket/fuel smart-stop feature, with no usage quota (GraphHopper is on-device and
  unmetered, unlike the Google path it replaces).
- The Autobahn exit-signboard/lane-panel system (`SignboardGuidanceEngine`) gained a second
  `GuidanceState`-based `build` overload specifically because GraphHopper's instruction data
  has no off-ramp/exit/fork concept at all (confirmed via `javap` against the real jar) - unlike
  the original Google `NavInfo`-based overload, exit/junction detection is now driven primarily
  by the already-OSM-based `RouteRoadFeatureGuidance` motorway-junction matching, which was
  already independent of the routing engine to begin with. No per-lane Google data exists to
  fall back to, so the lane panel simply hides when there's nothing reliable to show.
- The four route-intelligence singletons (`TerrainGuidance`, `SpeedLimitAheadGuidance`,
  `RouteRoadFeatureGuidance`, `RouteCameraGuidance`) each gained a `Route`-based `refresh`
  overload alongside their original `Navigator`-based one - each only ever consumed
  `navigator.currentRouteSegment`'s geometry, one field.

Verified end to end on the physical Pixel 6 Pro via the real app flow (search → select → Start
navigation), not a standalone POC: a real GraphHopper route was calculated and guidance started
(status text "206 m · arrive 9:13 pm", live ETA from `GuidanceState`), `SpeedLimitAheadGuidance`
correctly reported a real "50" km/h limit ahead using its new `Route`-based overload, and - since
the physical test device is not actually in Bremen, the only region the bundled routing graph
covers - once real (non-Bremen) GPS fixes arrived after the initial test fix, the guidance engine
correctly detected the device was off-route and surfaced "Rerouting…" without crashing, confirming
off-route detection and its failure-handling path both work correctly on real hardware. No
Google Maps/Navigation SDK code remains reachable from this file.

**`RoadPulseNavigationScreen`** (the Android Auto screen) is the one remaining screen still on
Google Navigation SDK - see "Cross-cutting observations" from the pre-migration investigation for
why it's expected to follow the same pattern as `NavigationActivity` (it shares the same
`SignboardGuidanceEngine`/`MapMarkerIconFactory`/guidance-singleton overloads already built,
requiring an Android-Auto-specific map/surface integration on top). The working Google
implementation on `main` is untouched throughout.
