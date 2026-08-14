package com.roadpulse.auto.design

/**
 * RoadPulse design tokens: typography scale and motion timing, which aren't naturally
 * expressed as Android resources. Colour tokens live in res/values/colors.xml and spacing/
 * radii/elevation in res/values/dimens.xml — see docs/DESIGN_SYSTEM.md for the full
 * documented palette and usage guidance.
 *
 * Sizes are in SP so they scale with the system font-size setting; every screen in this app
 * already sets TextView.textSize from a float (SP by default), so using these constants is a
 * drop-in replacement for today's inline magic numbers, not a new pattern.
 */
object Typography {
    /** Large numeric readouts: current speed, trip-complete distance. */
    const val DISPLAY_SP = 34f

    /** Maneuver instruction text ("Keep right toward A8"). */
    const val MANEUVER_SP = 22f

    /** Screen/section titles. */
    const val TITLE_SP = 18f

    /** Standard readable body text. */
    const val BODY_SP = 15f

    /** Secondary/supporting text: addresses, descriptions, detail lines. */
    const val METADATA_SP = 13f

    /** Smallest permitted label text (eyebrow labels, badges) — never used for anything safety-critical while driving. */
    const val LABEL_SP = 11f
}

object Motion {
    /** Route-selection highlight transition — short, not decorative. */
    const val ROUTE_SELECT_TRANSITION_MS = 180L

    /** Speed-compliance halo pulse cadence — gentle restrained cue, must never read as a flash. */
    const val COMPLIANCE_PULSE_MS = 1_400L
}
