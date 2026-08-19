package com.navplus.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.common.model.LatLng
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.connectivity.NetworkConnectivityManager
import com.navplus.core.navigation.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val locationTracker: LocationTracker,
    private val connectivity: NetworkConnectivityManager,
) : ViewModel() {

    val connectivityState: StateFlow<ConnectivityState> = connectivity.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityState.OFFLINE)

    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val userLocation: StateFlow<LatLng?> = _userLocation.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            locationTracker.locationUpdates().collect { location ->
                _userLocation.value = location.latLng
            }
        }
    }

    fun onSearchQueryChange(query: String) { _searchQuery.value = query }
}
