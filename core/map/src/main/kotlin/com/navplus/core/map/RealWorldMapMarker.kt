package com.navplus.core.map

import com.navplus.core.common.model.LatLng

data class RealWorldMapMarker(
    val id: String,
    val position: LatLng,
    val icon: String,
    val color: String,
    val priority: Int,
)
