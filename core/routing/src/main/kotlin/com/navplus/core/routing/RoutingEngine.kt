package com.navplus.core.routing

import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStyle

interface RoutingEngine {
    /** True if the engine has a prepared graph for the given coordinate. */
    fun coversLocation(lat: Double, lng: Double): Boolean

    /** Calculate one or more route alternatives. Returns empty list if offline data is missing. */
    suspend fun calculateRoutes(request: RoutingRequest): RoutingResult

    /** Release resources (e.g. close graph storage). */
    fun close()
}
