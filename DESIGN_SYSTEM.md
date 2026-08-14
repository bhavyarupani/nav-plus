# RoadPulse design system

Design tokens and component conventions for the mobile and Android Auto surfaces. This is a
foundation layer — most existing screens (`MainActivity`, `SettingsActivity`) predate this pass
and still reference their own local colour constants; new work should use the tokens below.

## Colour roles

Defined in `app/src/main/res/values/colors.xml`.

| Token | Role | Value |
|---|---|---|
| `rp_role_map_background` | Map background (deep charcoal/navy) | `#0B1220` |
| `rp_role_route_primary` | Active route centre line (vivid cyan) | `#22D3EE` |
| `rp_role_route_casing` | Active route casing, drawn beneath the centre line | `#0E4C8C` |
| `rp_role_route_alternative` | Alternative/unselected routes | `#64748B` |
| `rp_role_text_primary_dark` | Primary text, dark mode | `#F5F7FA` |
| `rp_role_text_primary_light` | Primary text, light mode | `#1F2937` |
| `rp_role_success` | Success / selected / faster-route state | `#10B981` |
| `rp_role_caution` | Caution, traffic, speed-compliance amber | `#F59E0B` |
| `rp_role_critical` | Critical congestion / error (restrained, not alarming) | `#DC2626` |
| `rp_role_speed_sign_red` / `rp_role_speed_sign_white` | EU-style circular speed-limit sign | `#E11D2E` / `#FFFFFF` |

Screen-chrome tokens (`rp_background`, `rp_panel`, `rp_accent`, etc.) predate this pass and
remain in place to avoid an unreviewed rename across existing screens. They are functionally
close to several of the roles above (`rp_accent` ≈ route/success family, `rp_camera_accent` ≈
caution) — new screens should prefer the semantic `rp_role_*` tokens; consolidating the two sets
is a candidate follow-up, not done in this pass since it would touch every existing screen.

## Typography scale

Defined in `com.roadpulse.auto.design.Typography` (Kotlin constants, since this app builds its
UI programmatically rather than from XML layouts for most screens). All screens already set
`TextView.textSize` from a float, which Android treats as SP — so these constants are a
drop-in replacement for today's inline magic numbers, and font scaling already works correctly
without further change.

| Token | Size | Use |
|---|---|---|
| `DISPLAY_SP` | 34sp | Large numeric readouts: current speed, trip stats |
| `MANEUVER_SP` | 22sp | Turn instruction text |
| `TITLE_SP` | 18sp | Screen/section titles |
| `BODY_SP` | 15sp | Standard readable text |
| `METADATA_SP` | 13sp | Secondary/supporting text |
| `LABEL_SP` | 11sp | Eyebrow labels/badges — never safety-critical |

## Spacing, radii, elevation, touch targets

Defined in `app/src/main/res/values/dimens.xml`. Spacing scale: `rp_space_xxs` (4dp) through
`rp_space_xl` (32dp). Radii: `rp_radius_sm/md/lg` (8/16/24dp). `rp_touch_target_min` is 48dp,
matching Android's documented accessibility minimum touch target — apply it as a minimum
width/height on any tappable control smaller than that visually.

## Motion

Defined in `com.roadpulse.auto.design.Motion`. `ROUTE_SELECT_TRANSITION_MS` (180ms) for route
selection highlight changes — short, not decorative. `COMPLIANCE_PULSE_MS` (1400ms) for the
speed-compliance halo's gentle pulse cadence: this must read as a restrained cue, never a flash,
per the product principle against decorative motion that draws attention off the road.

## Map rendering

`rp_route_width_primary` / `rp_route_width_casing` / `rp_route_width_alternative` in
`dimens.xml` set the base route stroke widths; actual on-screen width should still scale with
zoom level (data-driven, not fixed), per the rendering spec's layer-order requirements.
