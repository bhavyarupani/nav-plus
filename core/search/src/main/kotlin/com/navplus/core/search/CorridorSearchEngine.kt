package com.navplus.core.search

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import com.navplus.core.search.model.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

data class CorridorResult(
    val result: SearchResult,
    val nearestRoutePoint: LatLng,
    val detourTimeSeconds: Long,
    val detourMeters: Double,
)

@Singleton
class CorridorSearchEngine @Inject constructor(
    private val searchRepository: SearchRepository,
) {
    companion object {
        private const val CORRIDOR_WIDTH_METERS = 5_000.0
        private const val MAX_RESULTS = 8
        private const val AVG_SPEED_KPH = 90.0
    }

    suspend fun search(
        query: String,
        route: Route,
        fromDistanceMeters: Double = 0.0,
    ): List<CorridorResult> = coroutineScope {
        // Sample route points every ~2km for the search area
        val samplePoints = sampleRoute(route, fromDistanceMeters, intervalMeters = 2_000.0)
        if (samplePoints.isEmpty()) return@coroutineScope emptyList()

        // Parallel search at each sample point
        val rawResults = samplePoints.map { (point, _) ->
            async { searchRepository.search(query, near = point) }
        }.flatMap { it.await() }

        // Deduplicate by id, then filter to corridor
        val seen = mutableSetOf<String>()
        val inCorridor = rawResults.filter { result ->
            val nearest = samplePoints.minByOrNull { (p, _) -> result.position.distanceTo(p) }
            val distToRoute = nearest?.first?.distanceTo(result.position) ?: Double.MAX_VALUE
            distToRoute <= CORRIDOR_WIDTH_METERS && seen.add(result.id)
        }

        // Calculate detour time: distance from nearest route point to POI and back
        inCorridor.map { result ->
            val (nearestPoint, distFromStart) = samplePoints.minByOrNull { (p, _) ->
                result.position.distanceTo(p)
            } ?: return@map null
            val detourMeters = nearestPoint.distanceTo(result.position) * 2
            val detourSec = (detourMeters / (AVG_SPEED_KPH / 3.6)).toLong()
            CorridorResult(result, nearestPoint, detourSec, detourMeters)
        }.filterNotNull()
            .sortedBy { it.detourTimeSeconds }
            .take(MAX_RESULTS)
    }

    private fun sampleRoute(
        route: Route,
        fromDistanceMeters: Double,
        intervalMeters: Double,
    ): List<Pair<LatLng, Double>> {
        val samples = mutableListOf<Pair<LatLng, Double>>()
        var accumulated = 0.0
        var lastSample = 0.0
        val geom = route.geometry
        for (i in 1 until geom.size) {
            val seg = geom[i - 1].distanceTo(geom[i])
            accumulated += seg
            if (accumulated < fromDistanceMeters) continue
            val distAhead = accumulated - fromDistanceMeters
            if (distAhead - lastSample >= intervalMeters) {
                samples.add(geom[i] to accumulated)
                lastSample = distAhead
            }
        }
        return samples
    }
}
