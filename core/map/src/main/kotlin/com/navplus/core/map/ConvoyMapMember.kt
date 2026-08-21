package com.navplus.core.map

import com.navplus.core.common.model.LatLng

data class ConvoyMapMember(
    val id: String,
    val name: String,
    val position: LatLng,
    val bearingDeg: Float,
    val color: String,
)
