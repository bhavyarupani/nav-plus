# Map and navigation architecture

Current state of the driving experience, audited against the "premium European driving-nav app"
spec. See `ZERO_COST_ARCHITECTURE.md` for the provider-selection decision record and
`THIRD_PARTY_DATA.md` for per-source compliance detail — this document covers what's actually
built in the map/navigation UI layer itself.

## Core stack

Map rendering: MapLibre Native. Routing/turn-by-turn: GraphHopper, on-device, authoritative (see
`ZERO_COST_ARCHITECTURE.md`'s "TomTom" section for why it stays authoritative over TomTom's paid
Navigation SDK). Guidance: `GraphHopperGuidanceEngine` (map-matching, ETA, off-route detection).
Voice: Android `TextToSpeech`. All three screens (`MainActivity`, `NavigationActivity`,
`RoadPulseNavigationScreen`) consume the same `RoutingEngine`/`GuidanceEngine` interfaces in
`engine/NavigationEngine.kt`.

## Lane guidance — partial

`signage/LaneTopologyParser.kt` parses OSM `turn:lanes`/`destination:lanes` into per-lane states
(straight/left/right/slight/sharp/exit-only/added/ending); `SignboardRenderer.drawLaneStrip`
renders them, color-coded, live-wired via `SignboardGuidanceEngine.build` into both
`NavigationActivity` and `RoadPulseNavigationScreen`. This is real, working code, not scaffolding.

Gaps: rendered as part of the signboard bitmap, not a dedicated full-width lane bar; merge lanes
use a generic rotated arrow rather than a distinct merge glyph. "Auxiliary lane detection" as a
named feature does not exist anywhere in the codebase — the closest existing concept
(`LaneCountChange.ADDED`/`ENDING`) is a narrower, rightmost-lane-only heuristic, documented as
unverified per-junction. Building real auxiliary-lane detection would need richer OSM lane-topology
data than `turn:lanes`/`destination:lanes` alone provides at most junctions — not attempted yet.

## Signboards — partial

`signage/SignboardRenderer.kt` draws real motorway-shield-style panels: blue for Autobahn refs,
yellow for Bundesstraße, exit numbers, destination text — genuine graphics, not plain text, driven
by `SignboardGuidanceEngine` and live-wired into both driving screens.

Gaps: Canvas-drawn bitmaps rather than vector assets; single-panel layout only; destination text
truncated to 24 characters/3 lines; road-ref detection is a regex (`\b[AB]\s?\d+\b`) rather than
GraphHopper/OSM road-classification metadata; "Ausfahrt" is hardcoded English-context German, not
localized per country. Only Germany's blue/yellow convention exists — other countries' sign styles
(explicitly requested for later) are not started.

## Speed limit UI — partial

`driving/SpeedComplianceRingView.kt` + `SpeedComplianceAdvisor` show current speed vs. limit with
an over-limit red state and a "Check speed" pulse. `SpeedLimitAheadGuidance`/
`SpeedLimitRouteAnalyzer` distinguish known / unlimited / unknown. `MapMarkerIconFactory` already
distinguishes "no fixed limit" / "walking" / "variable" as map-marker glyphs, but that distinction
doesn't reach the ring view or the ahead-guidance summary text — there's no visual difference today
between "known fixed limit" and "known but conditional" once past the marker layer. Extending the
ring/ahead-guidance surfaces to carry that same known/conditional/variable/unknown state through is
the concrete next step here, not a rebuild.

## Not started in this layer

Route-preview screen with alternatives (routes are already returned as `List<Route>` by
`RoutingEngine.calculateRoute`, but nothing presents them for a choice — navigation starts
immediately on the first result). Distinct arrival/trip-complete screens (arrival today is a status
text change plus a TTS announcement). Junction/interchange visualization beyond the lane strip.
Day/night map style differentiation beyond color inversion (`maplibre_style_day.json`/
`maplibre_style_night.json` — not yet audited for genuine glare-reduction design, only confirmed to
exist as two separate files).
