package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng

data class LookaheadEvent(
    val distanceMeters: Double,
    val type: LookaheadEventType,
    val title: String,
    val emoji: String,
    val subtitle: String? = null,
    val position: LatLng,
    val severity: LookaheadSeverity = LookaheadSeverity.INFO,
)

enum class LookaheadEventType {
    SPEED_CAMERA,
    FUEL_STATION,
    REST_AREA,
    BORDER_CROSSING,
    ROADWORK,
    WEATHER,
    TOLL,
    TUNNEL,
    COFFEE,
    VIEWPOINT,
}

enum class LookaheadSeverity { INFO, WARNING, ALERT }
