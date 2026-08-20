package com.navplus.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Route
import com.navplus.core.settings.SettingsRepository
import com.navplus.core.settings.UserSettings
import com.navplus.core.group.GroupSyncService
import com.navplus.core.group.model.GroupSession
import com.navplus.core.navigation.LookaheadEngine
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.navigation.NavigationEngine
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.RoadCharacterAnalyzer
import com.navplus.core.navigation.TripRepository
import com.navplus.core.regions.BorderCrossing
import com.navplus.core.regions.BorderCrossingDetector
import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.RoutingRequest
import com.navplus.core.routing.RoutingResult
import com.navplus.core.safety.SafetyEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RoutingUiState {
    object Idle : RoutingUiState()
    object Calculating : RoutingUiState()
    object NoOfflineCoverage : RoutingUiState()
    data class Error(val message: String) : RoutingUiState()
    data class RouteReady(
        val route: Route,
        val distanceMeters: Double,
        val durationSeconds: Long,
        val destinationName: String,
    ) : RoutingUiState()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine,
    private val locationTracker: LocationTracker,
    private val safetyEngine: SafetyEngine,
    private val routingEngine: RoutingEngine,
    private val tripRepository: TripRepository,
    private val lookaheadEngine: LookaheadEngine,
    private val roadCharacterAnalyzer: RoadCharacterAnalyzer,
    private val borderCrossingDetector: BorderCrossingDetector,
    private val groupSyncService: GroupSyncService,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val navState: StateFlow<NavigationState> = navigationEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.Idle)

    val safetyAlerts = safetyEngine.alerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _routingUiState = MutableStateFlow<RoutingUiState>(RoutingUiState.Idle)
    val routingUiState: StateFlow<RoutingUiState> = _routingUiState.asStateFlow()

    private val _lookaheadEvents = MutableStateFlow<List<LookaheadEvent>>(emptyList())
    val lookaheadEvents: StateFlow<List<LookaheadEvent>> = _lookaheadEvents.asStateFlow()

    private val _roadCharacters = MutableStateFlow<List<RoadCharacter>>(emptyList())
    val roadCharacters: StateFlow<List<RoadCharacter>> = _roadCharacters.asStateFlow()

    private val _borderCrossings = MutableStateFlow<List<BorderCrossing>>(emptyList())
    val borderCrossings: StateFlow<List<BorderCrossing>> = _borderCrossings.asStateFlow()

    val groupSession: StateFlow<GroupSession?> = groupSyncService.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    init {
        // Prevent NavigationScreen from immediately exiting while waiting for first location fix
        if (tripRepository.pending.value != null) {
            _routingUiState.value = RoutingUiState.Calculating
        }

        viewModelScope.launch {
            locationTracker.locationUpdates().collect { location ->
                _currentLocation.value = location

                // Wire search → route → navigation: trigger routing as soon as we have a fix
                val pending = tripRepository.consume()
                if (pending != null && navState.value == NavigationState.Idle) {
                    calculateAndStart(location.latLng, pending.destination, pending.destinationName)
                    return@collect
                }

                val state = navState.value
                if (state is NavigationState.Navigating) {
                    safetyEngine.updatePosition(
                        position = location.latLng,
                        headingDeg = location.bearingDeg,
                        speedKph = location.speedKph,
                    )
                    groupSyncService.broadcastLocation(
                        lat = location.latLng.lat,
                        lng = location.latLng.lng,
                        bearing = location.bearingDeg,
                        speedKph = location.speedKph,
                        etaSec = state.progress.durationRemainingSeconds,
                        distanceRemainingMeters = state.progress.distanceRemainingMeters,
                        hasDeviated = false,
                    )
                }
            }
        }

        viewModelScope.launch {
            navState.debounce(2_000).collect { state ->
                if (state is NavigationState.Navigating) {
                    val route = state.progress.route
                    val distFromStart = route.distanceMeters - state.progress.distanceRemainingMeters
                    _lookaheadEvents.value = lookaheadEngine.eventsAhead(route, distFromStart)
                    _roadCharacters.value = roadCharacterAnalyzer.analyzeAhead(route, state.progress.currentStepIndex)
                    _borderCrossings.value = borderCrossingDetector.detectCrossings(route, distFromStart)
                } else {
                    _lookaheadEvents.value = emptyList()
                    _roadCharacters.value = emptyList()
                    _borderCrossings.value = emptyList()
                }
            }
        }
    }

    private suspend fun calculateAndStart(origin: LatLng, destination: LatLng, destinationName: String) {
        _routingUiState.value = RoutingUiState.Calculating
        val s = settings.value
        val request = RoutingRequest(
            origin = origin,
            destination = destination,
            avoidTolls = s.avoidTolls,
            avoidHighways = s.avoidHighways,
            avoidFerries = s.avoidFerries,
        )
        when (val result = routingEngine.calculateRoutes(request)) {
            is RoutingResult.Success -> {
                val route = result.routes.first()
                _routingUiState.value = RoutingUiState.RouteReady(
                    route = route,
                    distanceMeters = route.distanceMeters,
                    durationSeconds = route.durationSeconds,
                    destinationName = destinationName,
                )
            }
            is RoutingResult.NoOfflineCoverage -> _routingUiState.value = RoutingUiState.NoOfflineCoverage
            is RoutingResult.Error -> _routingUiState.value = RoutingUiState.Error(
                result.cause.message ?: "Routing failed"
            )
        }
    }

    fun startNavigation(route: Route) {
        _routingUiState.value = RoutingUiState.Idle
        navigationEngine.startNavigation(route)
    }

    fun stopNavigation() {
        navigationEngine.stopNavigation()
        _routingUiState.value = RoutingUiState.Idle
    }
}
