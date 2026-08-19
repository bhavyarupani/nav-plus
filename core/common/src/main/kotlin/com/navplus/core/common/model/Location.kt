package com.navplus.core.common.model

data class Location(
    val latLng: LatLng,
    val bearingDeg: Float = 0f,
    val speedMps: Float = 0f,
    val accuracyMeters: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val speedKph: Float get() = speedMps * 3.6f
}
