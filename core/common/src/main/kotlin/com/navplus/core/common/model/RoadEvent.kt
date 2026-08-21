package com.navplus.core.common.model

data class RoadEvent(
    val id: String,
    val type: RoadEventType,
    val position: LatLng,
    val routeDistanceMeters: Double,
    val severity: Severity = Severity.INFO,
    val confidence: Float = 1f,
    val title: String,
    val description: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val expiryMs: Long? = null,
    val source: String,
)

enum class RoadEventType {
    SPEED_CAMERA,
    RED_LIGHT_CAMERA,
    AVERAGE_SPEED_ZONE_START,
    AVERAGE_SPEED_ZONE_END,
    ROADWORK,
    ACCIDENT,
    CLOSURE,
    TRAFFIC,
    WEATHER,
    TUNNEL,
    TOLL,
    SCHOOL_ZONE,
    DANGEROUS_CURVE,
    STEEP_DESCENT,
    BORDER_CROSSING,
    FUEL_STATION,
    REST_AREA,
    HAZARD,
}

enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
