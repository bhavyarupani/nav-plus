package com.roadpulse.auto.alerts

enum class AlertType {
    ROAD_HAZARD,
    SPEED_CAMERA,
}

data class RoadAlert(
    val id: String,
    val type: AlertType,
    val title: String,
    val detail: String,
    val distanceMeters: Int,
    val isSimulated: Boolean,
)
