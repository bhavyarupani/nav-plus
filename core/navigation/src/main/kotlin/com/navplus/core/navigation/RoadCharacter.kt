package com.navplus.core.navigation

data class RoadCharacter(
    val roadName: String,
    val type: RoadType,
    val distanceMeters: Double,
    val complexityScore: Float, // 0–1, turns per km normalised
    val hairpinCount: Int = 0,
    val isSteep: Boolean = false,
    val isNarrow: Boolean = false,
    val description: String,
)

enum class RoadType {
    MOTORWAY,
    DUAL_CARRIAGEWAY,
    PRIMARY,
    MOUNTAIN,
    RURAL,
    URBAN,
    RESIDENTIAL,
    TRAFFIC_CALMING,
    SCHOOL_ZONE,
    NOISE_PROTECTION,
    TUNNEL,
    FERRY,
}

fun RoadType.emoji(): String = when (this) {
    RoadType.MOTORWAY          -> "🛣"
    RoadType.DUAL_CARRIAGEWAY  -> "🛤"
    RoadType.PRIMARY           -> "🏎"
    RoadType.MOUNTAIN          -> "⛰"
    RoadType.RURAL             -> "🌾"
    RoadType.URBAN             -> "🏙"
    RoadType.RESIDENTIAL       -> "🏘"
    RoadType.TRAFFIC_CALMING   -> "⚠"
    RoadType.SCHOOL_ZONE       -> "🏫"
    RoadType.NOISE_PROTECTION  -> "🔇"
    RoadType.TUNNEL            -> "🚇"
    RoadType.FERRY             -> "⛴"
}
