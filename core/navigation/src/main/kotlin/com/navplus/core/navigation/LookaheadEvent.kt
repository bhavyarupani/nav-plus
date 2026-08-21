package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.navigation.traffic.TrafficSignalRoadEvent

data class LookaheadEvent(
    val distanceMeters: Double,
    val type: LookaheadEventType,
    val title: String,
    val emoji: String,
    val subtitle: String? = null,
    val position: LatLng,
    val severity: LookaheadSeverity = LookaheadSeverity.INFO,
    val trafficSignal: TrafficSignalRoadEvent? = null,
)

enum class LookaheadEventType {
    TRAFFIC_SIGNAL,
    SPEED_CAMERA,
    SPEED_LIMIT,
    FUEL_STATION,
    REST_AREA,
    BORDER_CROSSING,
    ROADWORK,
    WEATHER,
    TOLL,
    TUNNEL,
    FERRY,
    ROUNDABOUT,
    JUNCTION,
    LANE_GUIDANCE,
    RESIDENTIAL_ZONE,
    TRAFFIC_CALMING,
    SCHOOL_ZONE,
    NOISE_PROTECTION_ZONE,
    STOP_SIGN,
    GIVE_WAY_SIGN,
    PRIORITY_ROAD,
    COFFEE,
    VIEWPOINT,
}

enum class LookaheadSeverity { INFO, WARNING, ALERT }
