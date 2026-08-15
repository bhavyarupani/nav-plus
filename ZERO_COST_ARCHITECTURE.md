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
| Routing / turn-by-turn | GraphHopper core library (`com.graphhopper:graphhopper-core` + `graphhopper-web-api` for instructions), used as an embedded Java library with our own Android integration layer | Apache 2.0 | On-device, embedded routing graph built from the same Geofabrik extract | No, once the routing graph is built and stored on-device | Yes | No | None | €0 | Apache 2.0 requires preserving the licence/copyright notice; no attribution UI requirement | See "Routing engine decision" below — this is the component with the most real integration risk |
| Search / geocoding | Custom offline index built from the same OSM extract, stored in Android's built-in SQLite with FTS (no extra dependency) | Our own code; OSM data still ODbL | On-device | No | Yes | No | None | €0 | Same OSM attribution as map data | Coverage and fuzzy-matching quality depend entirely on what we build — see remaining work |
| Voice guidance | Android `TextToSpeech` (platform API) | Platform API, no separate licence | On-device | No | Yes | No | None | €0 | None | Quality/voice availability depends on what TTS engine the user has installed; must degrade to text-only if none is available, as instructed |
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

**Decided and documented, not yet built:** the full routing pipeline (graph generation from a
real Geofabrik extract, on-device GraphHopper integration, rerouting, maneuver text), the offline
search index, and the day/night MapLibre styles.

**Built:** none of the runtime engine code yet as of this document's first version — this file
and the branch/interface scaffolding are the first commits of the migration, not the finished
migration. Treat every "not yet built" item as exactly that; this file will be updated commit by
commit as real, tested code lands, not rewritten retroactively to look more complete than it is.
