package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Route
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
                routeBearingDeg = RouteProgressCalculator.initialBearing(route),
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
        return RouteProgressCalculator.updateProgress(prev, location, route)
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
}

sealed class NavigationState {
    object Idle : NavigationState()
    object Rerouting : NavigationState()
    object RouteUnavailable : NavigationState()
    data class Navigating(val progress: RouteProgress) : NavigationState()
}
