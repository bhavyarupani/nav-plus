package com.navplus.core.map

import com.navplus.core.common.model.LatLng

data class CameraMarker(
    val position: LatLng,
    val speedLimitKph: Int?,
    val typeCode: String,
)
