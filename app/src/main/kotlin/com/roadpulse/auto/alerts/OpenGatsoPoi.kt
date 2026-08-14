package com.roadpulse.auto.alerts

enum class OpenGatsoPoiType {
    SPEED_CAMERA,
    AVERAGE_SPEED_CAMERA,
    RED_LIGHT_CAMERA,
    RAIL_CROSSING_CAMERA,
    TUNNEL,
    RAIL_CROSSING,
    OTHER_ROAD_HAZARD,
}

data class OpenGatsoPoi(
    val longitude: Double,
    val latitude: Double,
    val type: OpenGatsoPoiType,
    val speedLimitKph: Int?,
    val description: String,
) {
    val isEnforcementLocation: Boolean
        get() =
            type == OpenGatsoPoiType.SPEED_CAMERA ||
                type == OpenGatsoPoiType.AVERAGE_SPEED_CAMERA ||
                type == OpenGatsoPoiType.RED_LIGHT_CAMERA ||
                type == OpenGatsoPoiType.RAIL_CROSSING_CAMERA
}
