package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.RoutingRequest
import com.navplus.core.routing.RoutingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationEngine @Inject constructor(
    private val locationTracker: LocationTracker,
    private val routingEngine: RoutingEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var trackingJob: kotlinx.coroutines.Job? = null

    fun startNavigation(route: Route) {
        // Cancel any running trackLocation before starting a new one to prevent races.
        trackingJob?.cancel()
        _state.value = NavigationState.Navigating(
            RouteProgress(
                route = route,
                currentStepIndex = 0,
                distanceToNextStepMeters = route.steps.firstOrNull()?.distanceMeters ?: 0.0,
                distanceRemainingMeters = route.distanceMeters,
                durationRemainingSeconds = route.durationSeconds,
                snappedLocation = route.waypoints.firstOrNull() ?: LatLng(0.0, 0.0),
                nextManeuver = route.steps.firstOrNull()?.maneuver
                    ?: com.navplus.core.common.model.Maneuver.STRAIGHT,
                nextInstruction = route.steps.firstOrNull()?.instruction ?: "",
                nextStreetName = route.steps.firstOrNull()?.streetName,
                laneGuidance = route.steps.firstOrNull()?.laneGuidance,
                signboard = route.steps.firstOrNull()?.signboard,
                speedLimitKph = route.steps.firstOrNull()?.speedLimitKph,
            )
        )
        trackingJob = scope.launch { trackLocation(route) }
    }

    fun stopNavigation() {
        trackingJob?.cancel()
        trackingJob = null
        _state.value = NavigationState.Idle
    }

    private suspend fun trackLocation(route: Route) {
        locationTracker.locationUpdates().collect { location ->
            val current = _state.value
            if (current !is NavigationState.Navigating) return@collect

            val progress = updateProgress(current.progress, location, route)
            if (progress.isOffRoute) {
                reroute(location, route.waypoints.last())
            } else {
                _state.value = NavigationState.Navigating(progress)
            }
        }
    }

    private fun updateProgress(
        prev: RouteProgress,
        location: Location,
        route: Route,
    ): RouteProgress {
        if (route.steps.isEmpty() || route.geometry.isEmpty()) return prev
        val snapped = snapToRoute(location.latLng, route)
        val stepIndex = findCurrentStep(snapped, route, prev.currentStepIndex)
        if (stepIndex !in route.steps.indices) return prev
        val step = route.steps[stepIndex]
        val distToStep = snapped.distanceTo(step.endLocation)
        val distRemaining = route.steps.drop(stepIndex + 1).sumOf { it.distanceMeters } + distToStep
        val durRemaining = (distRemaining / route.distanceMeters * route.durationSeconds).toLong()

        val offRoute = snapped.distanceTo(location.latLng) > OFF_ROUTE_THRESHOLD_M

        return prev.copy(
            currentStepIndex = stepIndex,
            distanceToNextStepMeters = distToStep,
            distanceRemainingMeters = distRemaining,
            durationRemainingSeconds = durRemaining,
            snappedLocation = snapped,
            nextManeuver = step.maneuver,
            nextInstruction = step.instruction,
            nextStreetName = step.streetName,
            laneGuidance = step.laneGuidance,
            signboard = step.signboard,
            speedLimitKph = step.speedLimitKph,
            isOffRoute = offRoute,
        )
    }

    private fun snapToRoute(position: LatLng, route: Route): LatLng {
        var closest = route.geometry.first()
        var minDist = Double.MAX_VALUE
        for (i in 0 until route.geometry.size - 1) {
            val p = projectOntoSegment(position, route.geometry[i], route.geometry[i + 1])
            val d = position.distanceTo(p)
            if (d < minDist) { minDist = d; closest = p }
        }
        return closest
    }

    private fun projectOntoSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val ax = b.lng - a.lng; val ay = b.lat - a.lat
        val t = ((p.lng - a.lng) * ax + (p.lat - a.lat) * ay) / (ax * ax + ay * ay)
        val tc = t.coerceIn(0.0, 1.0)
        return LatLng(a.lat + tc * ay, a.lng + tc * ax)
    }

    private fun findCurrentStep(location: LatLng, route: Route, hintIndex: Int): Int {
        val steps = route.steps
        if (steps.isEmpty()) return 0
        val start = hintIndex.coerceIn(0, steps.lastIndex)
        for (i in start until steps.size) {
            if (location.distanceTo(steps[i].endLocation) < STEP_COMPLETION_THRESHOLD_M) continue
            return i
        }
        return steps.lastIndex
    }

    private suspend fun reroute(from: Location, destination: LatLng) {
        _state.value = NavigationState.Rerouting
        val result = routingEngine.calculateRoutes(
            RoutingRequest(origin = from.latLng, destination = destination)
        )
        when (result) {
            is RoutingResult.Success -> startNavigation(result.routes.first())
            else -> _state.value = NavigationState.RouteUnavailable
        }
    }

    companion object {
        private const val OFF_ROUTE_THRESHOLD_M = 50.0
        private const val STEP_COMPLETION_THRESHOLD_M = 20.0
    }
}

sealed class NavigationState {
    object Idle : NavigationState()
    object Rerouting : NavigationState()
    object RouteUnavailable : NavigationState()
    data class Navigating(val progress: RouteProgress) : NavigationState()
}
