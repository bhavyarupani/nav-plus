package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

data class PendingTrip(
    val destination: LatLng,
    val destinationName: String,
)

@Singleton
class TripRepository @Inject constructor() {
    private val _pending = MutableStateFlow<PendingTrip?>(null)
    val pending: StateFlow<PendingTrip?> = _pending.asStateFlow()

    fun setDestination(destination: LatLng, name: String) {
        _pending.value = PendingTrip(destination, name)
    }

    /** Atomically read and clear the pending trip. Returns null if none was set. */
    fun consume(): PendingTrip? = _pending.getAndUpdate { null }
}
