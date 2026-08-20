package com.navplus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.SettingsRepository
import com.navplus.core.settings.UserSettings
import com.navplus.core.settings.VehicleType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    fun setShowSpeedCameras(v: Boolean)    = launch { repo.setShowSpeedCameras(v) }
    fun setShowSpeedLimit(v: Boolean)      = launch { repo.setShowSpeedLimit(v) }
    fun setShowSignboards(v: Boolean)      = launch { repo.setShowSignboards(v) }
    fun setShowLaneGuidance(v: Boolean)    = launch { repo.setShowLaneGuidance(v) }
    fun setShowBorderAlerts(v: Boolean)    = launch { repo.setShowBorderAlerts(v) }
    fun setShowRoadPersonality(v: Boolean) = launch { repo.setShowRoadPersonality(v) }
    fun setAvoidTolls(v: Boolean)          = launch { repo.setAvoidTolls(v) }
    fun setAvoidHighways(v: Boolean)       = launch { repo.setAvoidHighways(v) }
    fun setAvoidFerries(v: Boolean)        = launch { repo.setAvoidFerries(v) }
    fun setNavMapTilt(v: Boolean)          = launch { repo.setNavMapTilt(v) }
    fun setKeepScreenOn(v: Boolean)        = launch { repo.setKeepScreenOn(v) }
    fun setUnits(v: DistanceUnits)         = launch { repo.setUnits(v) }
    fun setVehicleType(v: VehicleType)     = launch { repo.setVehicleType(v) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
