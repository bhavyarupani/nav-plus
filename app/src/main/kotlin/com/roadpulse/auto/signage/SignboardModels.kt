package com.roadpulse.auto.signage

/** German road-sign colour family a junction's guidance should render as. */
enum class SignboardType {
    AUTOBAHN_BLUE,
    DIRECTION_YELLOW,
    MIXED,
    NONE,
}

/** Per-lane arrow shape. Mirrors Google's LaneDirection.LaneShape values 1:1 (verified against
 * the Navigation SDK and androidx.car.app turn-by-turn models), so no lossy translation happens. */
enum class LaneShape {
    UNKNOWN,
    STRAIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    NORMAL_LEFT,
    NORMAL_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
}

/** What a single physical lane means for the driver's active route. */
enum class LaneState {
    RECOMMENDED,
    PERMITTED,
    NOT_RECOMMENDED,
    EXIT_ONLY,
    ADDED,
    ENDING,
    MERGING,
    UNKNOWN,
}

/** Where a piece of guidance came from, tracked per-element so GraphHopper and OSM content are
 * never visually conflated (see THIRD_PARTY_DATA.md, "Google Maps Platform compliance" section).
 * [GRAPHHOPPER] marks the case where the only signal is GraphHopper's own fork/ramp maneuver sign
 * with no OSM junction match to enrich it - see `SignboardGuidanceEngine.build`. */
enum class GuidanceDataSource {
    OPENSTREETMAP,
    GRAPHHOPPER,
    BLENDED,
}

enum class GuidanceConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** How much of the signboard/lane UI is safe to show right now. Always the highest tier whose
 * data is reliable enough; see [SignboardFallbackEngine]. */
enum class SignboardFallbackLevel {
    FULL_SIGNBOARD,
    JUNCTION_VIEW,
    SIMPLE_LANE_ARROWS,
    STANDARD_MANEUVER,
}

/**
 * One destination name as printed on a sign, optionally scoped to specific lanes.
 * [laneIndices] is empty when the destination applies to the sign generally rather than to
 * particular lanes (left-to-right, 0-indexed).
 */
data class SignpostedDestination(
    val text: String,
    val laneIndices: Set<Int> = emptySet(),
)

/**
 * One physical panel of a (possibly multi-panel) sign. A yellow direction sign with a small blue
 * Autobahn inset is represented as a single [DIRECTION_YELLOW] panel with [insetRoadRef]/
 * [insetDestination] populated, per the spec's "mixed yellow sign with blue inset" requirement.
 */
data class SignboardPanel(
    val type: SignboardType,
    val roadRef: String?,
    val exitNumber: String?,
    val destinations: List<SignpostedDestination>,
    val insetRoadRef: String? = null,
    val insetDestination: String? = null,
)

/** One physical lane's arrow(s) and route relevance. A lane can show more than one direction
 * arrow at once (e.g. straight-and-right), matching Google's own per-lane model. */
data class LaneGuidanceLane(
    val shapes: List<LaneShape>,
    val state: LaneState,
    val destination: SignpostedDestination? = null,
)

/** All lanes at the upcoming decision point, left-to-right in driving order. */
data class LaneGuidance(
    val lanes: List<LaneGuidanceLane>,
    val source: GuidanceDataSource,
    val confidence: GuidanceConfidence,
    val timestampMillis: Long,
) {
    val laneCountKnown: Boolean get() = lanes.isNotEmpty()
}

/** Everything known about the next junction/exit, before fallback-level gating is applied. */
data class JunctionGuidance(
    val panels: List<SignboardPanel>,
    val laneGuidance: LaneGuidance?,
    val distanceMetersToJunction: Int,
    val source: GuidanceDataSource,
    val confidence: GuidanceConfidence,
    val timestampMillis: Long,
)

/** The final, fallback-gated guidance state handed to the mobile and Android Auto UI. */
data class SignboardGuidance(
    val junction: JunctionGuidance?,
    val fallbackLevel: SignboardFallbackLevel,
) {
    companion object {
        val NONE = SignboardGuidance(junction = null, fallbackLevel = SignboardFallbackLevel.STANDARD_MANEUVER)
    }
}
