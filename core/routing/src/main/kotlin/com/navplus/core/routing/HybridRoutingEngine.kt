package com.navplus.core.routing

import com.navplus.core.routing.graphhopper.GraphHopperEngine
import com.navplus.core.routing.osrm.OsrmRoutingEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries the offline GraphHopper engine first. Falls back to OSRM when the local
 * graph doesn't cover the requested area or hasn't been downloaded yet.
 */
@Singleton
class HybridRoutingEngine @Inject constructor(
    private val offline: GraphHopperEngine,
    private val online: OsrmRoutingEngine,
) : RoutingEngine {

    override fun coversLocation(lat: Double, lng: Double): Boolean =
        offline.coversLocation(lat, lng) || online.coversLocation(lat, lng)

    override suspend fun calculateRoutes(request: RoutingRequest): RoutingResult {
        if (offline.coversLocation(request.origin.lat, request.origin.lng)) {
            val result = offline.calculateRoutes(request)
            if (result is RoutingResult.Success) return result
        }
        return online.calculateRoutes(request)
    }

    override fun close() {
        offline.close()
    }
}
