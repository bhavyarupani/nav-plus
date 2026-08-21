package com.navplus.core.routing

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.RouteStyle

data class RoutingRequest(
    val origin: LatLng,
    val destination: LatLng,
    val waypoints: List<LatLng> = emptyList(),
    val style: RouteStyle = RouteStyle.FASTEST,
    val alternatives: Int = 3,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val vehicleProfile: VehicleProfile = VehicleProfile.CAR,
)

enum class VehicleProfile { CAR, MOTORCYCLE, TRUCK, BICYCLE }
