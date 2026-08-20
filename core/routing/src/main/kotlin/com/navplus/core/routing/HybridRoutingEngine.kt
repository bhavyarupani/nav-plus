package com.navplus.core.routing

import com.navplus.core.routing.graphhopper.GraphHopperEngine
import com.navplus.core.routing.osrm.OsrmRoutingEngine
import com.navplus.core.routing.tomtom.TomTomRoutingEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier 1: offline GraphHopper (no network, downloaded regions)
 * Tier 2: TomTom (traffic-aware, quota-gated — seamlessly skipped when quota exceeded)
 * Tier 3: OSRM demo (always available, no traffic)
 */
@Singleton
class HybridRoutingEngine @Inject constructor(
    private val offline: GraphHopperEngine,
    private val tomtom: TomTomRoutingEngine,
    private val osrm: OsrmRoutingEngine,
) : RoutingEngine {

    override fun coversLocation(lat: Double, lng: Double) = true

    override suspend fun calculateRoutes(request: RoutingRequest): RoutingResult {
        if (offline.coversLocation(request.origin.lat, request.origin.lng)) {
            val result = offline.calculateRoutes(request)
            if (result is RoutingResult.Success) return result
        }
        if (tomtom.coversLocation(request.origin.lat, request.origin.lng)) {
            val result = tomtom.calculateRoutes(request)
            if (result is RoutingResult.Success) return result
        }
        return osrm.calculateRoutes(request)
    }

    override fun close() {
        offline.close()
    }
}
