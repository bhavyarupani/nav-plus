package com.navplus.core.safety.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.navplus.core.common.model.LatLng

@Entity(tableName = "speed_cameras")
data class SpeedCamera(
    @PrimaryKey val id: String,
    val lat: Double,
    val lng: Double,
    val type: CameraType,
    val directionDeg: Float?,
    val speedLimitKph: Int?,
    val country: String,
    val source: String,
    val confidence: Float = 1f,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
) {
    val position: LatLng get() = LatLng(lat, lng)
}

enum class CameraType {
    FIXED_SPEED,
    RED_LIGHT,
    COMBINED,
    AVERAGE_SPEED_START,
    AVERAGE_SPEED_END,
    MOBILE_ZONE,
    SECTION_CONTROL,
}
