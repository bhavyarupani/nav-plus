# Android Auto

## Status: code-complete on the free stack, never live-verified

`car/RoadPulseNavigationScreen.kt` (~1,200 lines) is a real, independent implementation — not a
thin wrapper around the phone UI. It owns its own `GraphHopperRoutingEngine`/
`GraphHopperGuidanceEngine`/`VoiceGuidance`/GPS instances (deliberately independent of
`NavigationActivity`'s, matching this class's pre-migration design — see "Phone + Android Auto
synchronization" below for why that's a real gap, not an oversight), and renders into the
`VirtualDisplay`/`SurfaceCallback` model Android Auto projection requires instead of a normal
Activity view hierarchy.

Wired for real, not scaffolded:
- `NavigationManager`/`NavigationManagerCallback` for the car's native navigation state.
- `AndroidAutoRoutingInfoFactory` maps `GuidanceState`/`ManeuverStep` to real
  `androidx.car.app.navigation.model.Maneuver` `TYPE_*` constants (verified against the actual
  `androidx.car.app:app:1.7.0` jar via `javap`, not guessed).
- `SignboardGuidanceEngine`/`SignboardRenderer` — the same lane-strip/signboard code
  `NavigationActivity` uses — reused for the car screen's junction-image slot.
- `MapLibreMapController` renders the same free-stack map into the car's surface.
- `SurfaceCallback.onScroll`/`onScale` drive real pan/zoom via `MapLibreMapController.scrollBy`/
  `zoomBy` (MapLibre has no direct pixel-scroll equivalent to Google's `CameraUpdateFactory`, so
  `scrollBy` converts the camera target to a screen point via `Projection`, offsets it, converts
  back).
- Live traffic (`AutobahnTrafficRepository` + `TomTomTrafficRepository`, merged and deduped),
  weather, road signs, and facilities all refresh for the visible car-screen map, same pattern as
  `MainActivity`.

## The DHU blocker

Live, on-car verification via Android Auto's Desktop Head Unit was attempted and is blocked by
**tooling**, not app code. The SDK's bundled DHU binary is a 2022-03-30 build (the newest Google
has published, confirmed via `sdkmanager`), while the test Pixel 6 Pro's installed Android Auto app
is version 17.3.662854 — a multi-year protocol gap. DHU is killed by the phone within ~1 second of
every connection attempt, headless or windowed, with no error logged: the signature of a protocol
handshake rejection. Two real prerequisites were found and fixed along the way (Android Auto's
hidden "Unknown sources" developer flag, and starting its head-unit server explicitly via the app's
own overflow menu), but neither changed the outcome.

`RoadPulseNavigationScreen` passes the same `ktlintFormat`/`ktlintCheck`/`compileDebugKotlin`/
`testDebugUnitTest`/`lintDebug` bar as every other file in this codebase — that's real verification
of the code compiling and being internally consistent, but it is **not** the same as confirming the
car screen actually looks and behaves correctly on a real head unit. Any future TomTom-traffic or
region-download-related change to this file (both already wired, both unverified live) should be
called out as "code-complete, DHU-blocked" rather than "tested," until either a working DHU/AA
version pairing or real head-unit access exists.

## Explicit gaps vs. the spec

- **One navigation session, synchronized state**: not built. Phone (`NavigationActivity`) and car
  (`RoadPulseNavigationScreen`) each own independent `GraphHopperRoutingEngine`/
  `GraphHopperGuidanceEngine` instances and independently calculate routes — if both were active at
  once, they could diverge. This was a deliberate scope decision during the original migration
  (phone and car screens are used alternatively in practice, not concurrently), not an oversight,
  but it means there is no shared `NavigationSession` object today for the phone/AA state-sync the
  spec asks for. Building that would mean introducing exactly the kind of shared-session
  abstraction neither screen currently has.
- **Safe category search through car templates**: not built — no in-car search UI exists yet
  (`RoadPulseNavigationScreen` only ever navigates to whatever `SelectedDestinationStore` already
  holds from the phone).
- Lane guidance/signboards on the car screen inherit the same gaps documented in
  `MAP_AND_NAVIGATION_ARCHITECTURE.md` (Canvas bitmap, not vector; no dedicated lane bar).
