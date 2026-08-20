package com.navplus.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.distanceTo
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.connectivity.NetworkConnectivityManager
import com.navplus.core.map.CameraMarker
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.safety.OverpassCameraFetcher
import com.navplus.core.safety.SpeedCameraDao
import com.navplus.core.safety.model.CameraType
import com.navplus.core.settings.SettingsRepository
import com.navplus.core.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NearbyCamera(
    val distanceMeters: Double,
    val speedLimitKph: Int?,
    val type: CameraType,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val locationTracker: LocationTracker,
    private val connectivity: NetworkConnectivityManager,
    private val cameraDao: SpeedCameraDao,
    private val overpassFetcher: OverpassCameraFetcher,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val connectivityState: StateFlow<ConnectivityState> = connectivity.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityState.OFFLINE)

    val settings: StateFlow<UserSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _nearbyCamera = MutableStateFlow<NearbyCamera?>(null)
    val nearbyCamera: StateFlow<NearbyCamera?> = _nearbyCamera.asStateFlow()

    private val _visibleCameras = MutableStateFlow<List<com.navplus.core.safety.model.SpeedCamera>>(emptyList())

    // Hides markers immediately when the user turns off the toggle in Settings.
    val visibleCameraMarkers: StateFlow<List<CameraMarker>> = combine(
        _visibleCameras, settings,
    ) { cameras, s ->
        if (s.showSpeedCameras) cameras.map { it.toMarker() } else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var viewportJob: Job? = null

    init {
        viewModelScope.launch {
            locationTracker.locationUpdates().collect { location ->
                _userLocation.value = location
                refreshNearbyCamera(location)
            }
        }
    }

    fun onViewportChanged(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(400) // debounce rapid pan/zoom events
            _visibleCameras.value = cameraDao.getCamerasInBoundingBox(minLat, maxLat, minLng, maxLng)
            launch(Dispatchers.IO) {
                overpassFetcher.fetchAndCache(minLat, maxLat, minLng, maxLng)
                _visibleCameras.value = cameraDao.getCamerasInBoundingBox(minLat, maxLat, minLng, maxLng)
            }
        }
    }

    private suspend fun refreshNearbyCamera(location: Location) {
        if (!settings.value.showSpeedCameras) {
            _nearbyCamera.value = null
            return
        }
        val delta = 0.018 // ~2 km
        val lat = location.latLng.lat
        val lng = location.latLng.lng
        val cameras = cameraDao.getCamerasInBoundingBox(lat - delta, lat + delta, lng - delta, lng + delta)
        val closest = cameras.minByOrNull { location.latLng.distanceTo(it.position) }
        val dist = closest?.let { location.latLng.distanceTo(it.position) } ?: Double.MAX_VALUE
        _nearbyCamera.value = if (dist < 2_000) {
            NearbyCamera(distanceMeters = dist, speedLimitKph = closest!!.speedLimitKph, type = closest.type)
        } else null
    }

    private fun com.navplus.core.safety.model.SpeedCamera.toMarker() = CameraMarker(
        position = position,
        speedLimitKph = speedLimitKph,
        typeCode = type.name,
    )
}
