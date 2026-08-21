package com.navplus.core.map

import com.navplus.core.common.model.LatLng

data class MapRouteLine(
    val id: String,
    val geometry: List<LatLng>,
)
