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
    FERRY,
}

fun RoadType.emoji(): String = when (this) {
    RoadType.MOTORWAY          -> "🛣"
    RoadType.DUAL_CARRIAGEWAY  -> "🛤"
    RoadType.PRIMARY           -> "🏎"
    RoadType.MOUNTAIN          -> "⛰"
    RoadType.RURAL             -> "🌾"
    RoadType.URBAN             -> "🏙"
    RoadType.FERRY             -> "⛴"
}
