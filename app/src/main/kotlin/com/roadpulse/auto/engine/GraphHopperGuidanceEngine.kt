package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device turn-by-turn guidance over a [Route] produced by a [RoutingEngine] (GraphHopper - see
 * ZERO_COST_ARCHITECTURE.md). Replaces Google Navigation SDK's `Navigator` real-time behaviour:
 * map-matching the current GPS fix onto the route to find current/next [ManeuverStep], computing
 * remaining distance/ETA, detecting when the driver has left the route and requesting a
 * recalculation, and detecting arrival. Nothing here calls a network service - map-matching and
 * ETA are pure geometry over the already-downloaded [Route], and rerouting delegates back to the
 * same on-device [RoutingEngine] that produced the original route.
 *
 * The cross-track/along-track projection math mirrors the proven implementation already used by
 * `driving/JunctionPriorityGuidance.kt` (equirectangular projection with a `cos(lat)` longitude
 * scale factor, accurate enough at road-matching distances) rather than introducing a third,
 * subtly-different copy - see that file for the same approach applied to matching road signs onto
 * a route.
 */
class GraphHopperGuidanceEngine(
    private val routingEngine: RoutingEngine,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : GuidanceEngine {
    private val listeners = CopyOnWriteArrayList<(GuidanceState) -> Unit>()
    private val rerouteInFlight = AtomicBoolean(false)

    @Volatile private var activeRoute: Route? = null

    @Volatile private var destination: RoadCoordinate? = null

    @Volatile private var cumulativeMeters: DoubleArray = DoubleArray(0)

    @Volatile private var stepStartMeters: DoubleArray = DoubleArray(0)

    @Volatile private var isRerouting = false

    private var lastState: GuidanceState? = null
    private var offRouteSinceMillis: Long? = null

    override fun startGuidance(route: Route) {
        activeRoute = route
        destination = route.geometry.lastOrNull()
        cumulativeMeters = cumulativeDistances(route.geometry)
        stepStartMeters = stepStartDistances(route, cumulativeMeters)
        isRerouting = false
        offRouteSinceMillis = null
        lastState = null
    }

    override fun stopGuidance() {
        activeRoute = null
        destination = null
        cumulativeMeters = DoubleArray(0)
        stepStartMeters = DoubleArray(0)
        lastState = null
        offRouteSinceMillis = null
    }

    override fun addListener(listener: (GuidanceState) -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: (GuidanceState) -> Unit) {
        listeners.remove(listener)
    }

    override fun onLocationUpdate(
        coordinate: RoadCoordinate,
        speedKph: Float?,
        bearingDegrees: Float?,
    ) {
        val route = activeRoute ?: return
        val geometry = route.geometry
        if (geometry.size < 2) return

        val projection = projectOntoRoute(coordinate, geometry, cumulativeMeters)
        val now = nowMillis()
        if (projection.crossTrackMeters > OFF_ROUTE_THRESHOLD_METERS) {
            val since = offRouteSinceMillis ?: now.also { offRouteSinceMillis = it }
            if (now - since >= OFF_ROUTE_CONFIRM_MILLIS) {
                requestReroute(coordinate)
            }
        } else {
            offRouteSinceMillis = null
        }

        val totalMeters = cumulativeMeters.lastOrNull() ?: route.distanceMeters.toDouble()
        val remainingMeters = (totalMeters - projection.alongMeters).coerceAtLeast(0.0)
        val fractionRemaining = if (totalMeters > 0) (remainingMeters / totalMeters) else 0.0
        val remainingSeconds = (route.durationSeconds * fractionRemaining).roundToInt()
        val etaEpochSeconds = now / 1000 + remainingSeconds

        val stepIndex = currentStepIndex(projection.alongMeters)
        val hasArrived = remainingMeters <= ARRIVAL_THRESHOLD_METERS

        val state =
            GuidanceState(
                currentStep = route.steps.getOrNull(stepIndex),
                nextStep = route.steps.getOrNull(stepIndex + 1),
                distanceToDestinationMeters = remainingMeters.roundToInt(),
                etaEpochSeconds = etaEpochSeconds,
                isRerouting = isRerouting,
                hasArrived = hasArrived,
            )
        emit(state)
    }

    /**
     * Delegates recalculation to [routingEngine], whose implementations (e.g.
     * [GraphHopperRoutingEngine]) already resolve their [java.util.concurrent.CompletableFuture]
     * on their own background executor - so a plain, non-async `whenComplete` here is enough to
     * keep this off the caller's thread (typically the main thread, from a location callback)
     * without this class needing an executor of its own.
     */
    private fun requestReroute(from: RoadCoordinate) {
        val dest = destination ?: return
        if (!rerouteInFlight.compareAndSet(false, true)) return
        isRerouting = true
        lastState?.copy(isRerouting = true)?.let(::emit)
        routingEngine.recalculateRoute(from, dest).whenComplete { route, _ ->
            rerouteInFlight.set(false)
            offRouteSinceMillis = null
            if (route != null) {
                startGuidance(route)
            } else {
                isRerouting = false
            }
        }
    }

    private fun emit(state: GuidanceState) {
        if (state == lastState) return
        lastState = state
        listeners.forEach { it(state) }
    }

    private fun currentStepIndex(alongMeters: Double): Int {
        if (stepStartMeters.isEmpty()) return 0
        var index = 0
        while (index + 1 < stepStartMeters.size && stepStartMeters[index + 1] <= alongMeters) {
            index++
        }
        return index
    }

    private fun stepStartDistances(
        route: Route,
        cumulative: DoubleArray,
    ): DoubleArray {
        if (route.steps.isEmpty() || cumulative.isEmpty()) return DoubleArray(0)
        val starts = DoubleArray(route.steps.size)
        var traveled = 0.0
        route.steps.forEachIndexed { index, step ->
            starts[index] = traveled
            traveled += step.distanceMeters
        }
        return starts
    }

    private fun cumulativeDistances(geometry: List<RoadCoordinate>): DoubleArray {
        val cumulative = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            cumulative[index] = cumulative[index - 1] + haversineMeters(geometry[index - 1], geometry[index])
        }
        return cumulative
    }

    private fun projectOntoRoute(
        point: RoadCoordinate,
        route: List<RoadCoordinate>,
        cumulative: DoubleArray,
    ): RouteProjection {
        var best = RouteProjection(Double.POSITIVE_INFINITY, 0.0)
        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
        for (index in 0 until route.size - 1) {
            val start = route[index]
            val end = route[index + 1]
            val ax = (start.longitude - point.longitude) * longitudeScale
            val ay = (start.latitude - point.latitude) * latitudeScale
            val bx = (end.longitude - point.longitude) * longitudeScale
            val by = (end.latitude - point.latitude) * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val fraction =
                if (lengthSquared <= .01) {
                    0.0
                } else {
                    (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                }
            val crossTrack =
                sqrt(
                    (ax + fraction * dx) * (ax + fraction * dx) +
                        (ay + fraction * dy) * (ay + fraction * dy),
                )
            if (crossTrack < best.crossTrackMeters) {
                val segmentLength = cumulative[index + 1] - cumulative[index]
                best = RouteProjection(crossTrack, cumulative[index] + segmentLength * fraction)
            }
        }
        return best
    }

    private fun haversineMeters(
        start: RoadCoordinate,
        end: RoadCoordinate,
    ): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private data class RouteProjection(
        val crossTrackMeters: Double,
        val alongMeters: Double,
    )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val OFF_ROUTE_THRESHOLD_METERS = 40.0
        const val OFF_ROUTE_CONFIRM_MILLIS = 5_000L
        const val ARRIVAL_THRESHOLD_METERS = 20.0
    }
}
