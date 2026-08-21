package com.navplus.core.common.model

data class Route(
    val id: String,
    val waypoints: List<LatLng>,
    val geometry: List<LatLng>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val style: RouteStyle = RouteStyle.FASTEST,
    val trafficDelaySeconds: Long = 0L,
    val hasTolls: Boolean = false,
    val hasHighways: Boolean = false,
    val ascendMeters: Double = 0.0,
    val descendMeters: Double = 0.0,
)

data class RouteStep(
    val instruction: String,
    val maneuver: Maneuver,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val startLocation: LatLng,
    val endLocation: LatLng,
    val geometry: List<LatLng>,
    val laneGuidance: LaneGuidance? = null,
    val signboard: Signboard? = null,
    val streetName: String? = null,
    val exitNumber: String? = null,
    val speedLimitKph: Int? = null,
)

enum class RouteStyle {
    FASTEST,
    SIMPLE,
    SCENIC,
    SAFER,
    LOW_STRESS,
    AVOID_HIGHWAYS,
    AVOID_TOLLS,
}

data class LaneGuidance(
    val lanes: List<Lane>,
    val recommendedIndices: List<Int>,
)

data class Lane(
    val directions: List<LaneDirection>,
    val isActive: Boolean,
)

enum class LaneDirection {
    STRAIGHT, LEFT, SLIGHT_LEFT, SHARP_LEFT,
    RIGHT, SLIGHT_RIGHT, SHARP_RIGHT,
    U_TURN, MERGE, EXIT
}

data class Signboard(
    val roadNumber: String? = null,
    val destinations: List<String> = emptyList(),
    val exitNumber: String? = null,
    val roadType: RoadType = RoadType.REGULAR,
)

enum class RoadType { MOTORWAY, BUNDESSTRASSE, REGULAR }
