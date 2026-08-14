package com.roadpulse.auto.traffic

data class RoadCoordinate(
    val latitude: Double,
    val longitude: Double,
)

enum class RoadInfrastructureType {
    TRAFFIC_SIGNAL,
    STOP_SIGN,
    GIVE_WAY_SIGN,
    PRIORITY_ROAD_SIGN,
    PRIORITY_AT_JUNCTION_SIGN,
    SPEED_LIMIT_SIGN,
    ROAD_RULE_START,
    ROAD_RULE_END,
    TRAFFIC_RESTRICTION,
    PEDESTRIAN_CROSSING,
    RAILWAY_CROSSING,
    SCHOOL_ZONE,
    TRAFFIC_CALMING,
    TUNNEL,
    BRIDGE,
    DIMENSION_RESTRICTION,
    TOLL,
    STEEP_GRADE,
    SURFACE_HAZARD,
    MOTORWAY_JUNCTION,
    OTHER_SIGN,
}

data class RoadInfrastructurePoint(
    val id: String,
    val coordinate: RoadCoordinate,
    val type: RoadInfrastructureType,
    val title: String,
    val detail: String,
    val direction: String?,
    val trafficSignCode: String?,
    /** Raw `ref` tag for a [RoadInfrastructureType.MOTORWAY_JUNCTION], e.g. "54" - null for
     * every other type and for junctions without a mapped exit number. Kept separate from
     * [title] (which is already formatted as "Exit 54") so signage code can compare exit
     * numbers without re-parsing display text. */
    val exitNumber: String? = null,
) {
    val hasLiveSignalPhase: Boolean = false

    /** Terrain is shown as route-ahead guidance while driving, never as a map pin. */
    val shouldDisplayOnMap: Boolean
        get() = type != RoadInfrastructureType.STEEP_GRADE

    /** These signs are useful only when matched to the driver's active route and approach. */
    val isJunctionPrioritySign: Boolean
        get() = type in JUNCTION_PRIORITY_TYPES

    /** Every mapped road feature except slope, which has its own travelling data panel. */
    val isRouteGuidanceFeature: Boolean
        get() = shouldDisplayOnMap

    companion object {
        private val JUNCTION_PRIORITY_TYPES =
            setOf(
                RoadInfrastructureType.GIVE_WAY_SIGN,
                RoadInfrastructureType.PRIORITY_ROAD_SIGN,
                RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
            )
    }
}

data class RoadInfrastructureResult(
    val points: List<RoadInfrastructurePoint>,
    val autobahnRefs: Set<String>,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
    val facilities: List<RoadFacility> = emptyList(),
    val speedLimitSections: List<SpeedLimitRoadSection> = emptyList(),
    val laneTopologySections: List<LaneTopologyWaySection> = emptyList(),
)

data class SpeedLimitRoadSection(
    val id: String,
    val geometry: List<RoadCoordinate>,
    val speedLimitKph: Int?,
    val label: String,
    val unlimited: Boolean = false,
    val conditionalOrVariable: Boolean = false,
)

/**
 * A mapped road-way segment carrying OSM lane-topology tags (`turn:lanes`, `destination:lanes`,
 * `lanes`, `change:lanes`). Raw tag strings are kept as-is; parsing/interpretation happens in
 * `com.roadpulse.auto.signage.LaneTopologyParser` so this module stays a plain OSM data mirror.
 */
data class LaneTopologyWaySection(
    val id: String,
    val geometry: List<RoadCoordinate>,
    val ref: String?,
    val lanes: String?,
    val lanesForward: String?,
    val turnLanes: String?,
    val destinationLanes: String?,
    val destinationRefLanes: String?,
    val changeLanes: String?,
)
