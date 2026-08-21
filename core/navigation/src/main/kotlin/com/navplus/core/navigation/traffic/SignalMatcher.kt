package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SignalMatcher @Inject constructor() {
    fun matchSignals(
        route: Route,
        currentDistanceFromStartMeters: Double,
        signals: List<TrafficSignal>,
        corridorMeters: Double = DEFAULT_SIGNAL_CORRIDOR_METERS,
        lookaheadMeters: Double = DEFAULT_SIGNAL_LOOKAHEAD_METERS,
    ): List<SignalMatch> =
        signals.mapNotNull { signal ->
            val match = signal.routeMatch(route, currentDistanceFromStartMeters) ?: return@mapNotNull null
            if (match.distanceFromRouteMeters > corridorMeters) return@mapNotNull null
            if (match.distanceAheadMeters !in 0.0..lookaheadMeters) return@mapNotNull null
            if (!signal.bearingMatchesRoute(route, match.matchedRouteEdgeIndex)) return@mapNotNull null

            val movement = route.stepForEdge(match.matchedRouteEdgeIndex)?.applicableMovement()
            if (!signal.movementMatches(movement)) return@mapNotNull null

            SignalMatch(
                signal = signal.copy(distanceAlongRoute = match.distanceAheadMeters),
                distanceAheadMeters = match.distanceAheadMeters,
                distanceFromRouteMeters = match.distanceFromRouteMeters,
                matchedRouteEdgeIndex = match.matchedRouteEdgeIndex,
                applicableMovement = movement,
                applicableLaneGroup = route.stepForEdge(match.matchedRouteEdgeIndex)
                    ?.laneGuidance
                    ?.recommendedIndices
                    ?.joinToString("/") { (it + 1).toString() },
            )
        }.sortedBy { it.distanceAheadMeters }

    fun isSignalRelevantToRoute(
        route: Route,
        currentDistanceFromStartMeters: Double,
        signal: TrafficSignal,
    ): Boolean = matchSignals(route, currentDistanceFromStartMeters, listOf(signal)).isNotEmpty()

    private fun TrafficSignal.routeMatch(route: Route, routeProgressMeters: Double): RouteSignalProjection? {
        if (route.geometry.size < 2) return null
        var accumulated = 0.0
        var best: RouteSignalProjection? = null
        val signalPosition = position

        for (index in 0 until route.geometry.lastIndex) {
            val start = route.geometry[index]
            val end = route.geometry[index + 1]
            val segmentMeters = start.distanceTo(end)
            if (segmentMeters <= 0.0) continue

            val projected = signalPosition.projectOntoSegment(start, end)
            val distanceFromRoute = signalPosition.distanceTo(projected)
            val distanceOnSegment = start.distanceTo(projected).coerceIn(0.0, segmentMeters)
            val distanceFromStart = accumulated + distanceOnSegment
            val candidate = RouteSignalProjection(
                distanceAheadMeters = distanceFromStart - routeProgressMeters,
                distanceFromRouteMeters = distanceFromRoute,
                matchedRouteEdgeIndex = index,
            )
            if (best == null || candidate.distanceFromRouteMeters < best.distanceFromRouteMeters) {
                best = candidate
            }
            accumulated += segmentMeters
        }
        return best
    }

    private fun TrafficSignal.bearingMatchesRoute(route: Route, edgeIndex: Int): Boolean {
        val signalBearing = bearing ?: return true
        val start = route.geometry.getOrNull(edgeIndex) ?: return true
        val end = route.geometry.getOrNull(edgeIndex + 1) ?: return true
        val routeBearing = start.bearingTo(end).toFloat()
        return angleDifference(signalBearing, routeBearing) <= MAX_DIRECTION_DIFFERENCE_DEGREES
    }

    private fun TrafficSignal.movementMatches(routeMovement: SignalMovement?): Boolean {
        val signalMovement = movement ?: return true
        if (signalMovement == SignalMovement.UNKNOWN || routeMovement == null) return true
        return signalMovement == routeMovement
    }

    private fun Route.stepForEdge(edgeIndex: Int): RouteStep? {
        if (steps.isEmpty()) return null
        if (geometry.size <= 1) return steps.first()
        val edgeRatio = edgeIndex.toDouble() / (geometry.lastIndex.toDouble()).coerceAtLeast(1.0)
        val stepIndex = (edgeRatio * steps.size).toInt().coerceIn(0, steps.lastIndex)
        return steps[stepIndex]
    }

    private fun RouteStep.applicableMovement(): SignalMovement? {
        val guidance = laneGuidance
        val laneMovement = guidance
            ?.recommendedIndices
            ?.firstOrNull()
            ?.let { guidance.lanes.getOrNull(it) }
            ?.directions
            ?.firstOrNull()
            ?.toSignalMovement()
        if (laneMovement != null) return laneMovement
        return when {
            maneuver.name.contains("LEFT") -> SignalMovement.LEFT
            maneuver.name.contains("RIGHT") -> SignalMovement.RIGHT
            maneuver.name.contains("U_TURN") -> SignalMovement.U_TURN
            else -> SignalMovement.STRAIGHT
        }
    }

    private fun LatLng.projectOntoSegment(start: LatLng, end: LatLng): LatLng {
        val ax = end.lng - start.lng
        val ay = end.lat - start.lat
        val denom = ax * ax + ay * ay
        if (denom == 0.0) return start
        val t = (((lng - start.lng) * ax + (lat - start.lat) * ay) / denom).coerceIn(0.0, 1.0)
        return LatLng(
            lat = start.lat + t * ay,
            lng = start.lng + t * ax,
        )
    }

    private fun angleDifference(a: Float, b: Float): Float {
        val diff = abs((a - b + 540f) % 360f - 180f)
        return diff
    }

    private data class RouteSignalProjection(
        val distanceAheadMeters: Double,
        val distanceFromRouteMeters: Double,
        val matchedRouteEdgeIndex: Int,
    )

    companion object {
        const val DEFAULT_SIGNAL_LOOKAHEAD_METERS = 3_000.0
        const val DEFAULT_SIGNAL_CORRIDOR_METERS = 30.0
        private const val MAX_DIRECTION_DIFFERENCE_DEGREES = 75f
    }
}
