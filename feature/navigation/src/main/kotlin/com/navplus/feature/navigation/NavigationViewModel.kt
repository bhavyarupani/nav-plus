package com.navplus.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.navigation.NavigationEngine
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.common.model.Location
import com.navplus.core.safety.SafetyEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine,
    private val locationTracker: LocationTracker,
    private val safetyEngine: SafetyEngine,
) : ViewModel() {

    val navState: StateFlow<NavigationState> = navigationEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.Idle)

    val safetyAlerts = safetyEngine.alerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

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
                }
            }
        }
    }

    fun stopNavigation() = navigationEngine.stopNavigation()
}
