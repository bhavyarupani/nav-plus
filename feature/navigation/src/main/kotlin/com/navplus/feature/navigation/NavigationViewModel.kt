package com.navplus.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.common.model.Location
import com.navplus.core.group.GroupSyncService
import com.navplus.core.group.model.GroupSession
import com.navplus.core.navigation.LookaheadEngine
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.navigation.NavigationEngine
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.RoadCharacterAnalyzer
import com.navplus.core.regions.BorderCrossingDetector
import com.navplus.core.regions.BorderCrossing
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

@OptIn(FlowPreview::class)
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine,
    private val locationTracker: LocationTracker,
    private val safetyEngine: SafetyEngine,
    private val lookaheadEngine: LookaheadEngine,
    private val roadCharacterAnalyzer: RoadCharacterAnalyzer,
    private val borderCrossingDetector: BorderCrossingDetector,
    private val groupSyncService: GroupSyncService,
) : ViewModel() {

    val navState: StateFlow<NavigationState> = navigationEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.Idle)

    val safetyAlerts = safetyEngine.alerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _lookaheadEvents = MutableStateFlow<List<LookaheadEvent>>(emptyList())
    val lookaheadEvents: StateFlow<List<LookaheadEvent>> = _lookaheadEvents.asStateFlow()

    private val _roadCharacters = MutableStateFlow<List<RoadCharacter>>(emptyList())
    val roadCharacters: StateFlow<List<RoadCharacter>> = _roadCharacters.asStateFlow()

    private val _borderCrossings = MutableStateFlow<List<BorderCrossing>>(emptyList())
    val borderCrossings: StateFlow<List<BorderCrossing>> = _borderCrossings.asStateFlow()

    val groupSession: StateFlow<GroupSession?> = groupSyncService.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            locationTracker.locationUpdates().collect { location ->
                _currentLocation.value = location
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

        // Refresh lookahead every time nav state changes meaningfully
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

    fun stopNavigation() = navigationEngine.stopNavigation()
}
