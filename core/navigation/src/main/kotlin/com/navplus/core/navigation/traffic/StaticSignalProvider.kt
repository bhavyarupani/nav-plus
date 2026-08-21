package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.Route
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StaticTrafficSignalStore @Inject constructor() {
    private val signals = LinkedHashMap<String, TrafficSignal>()

    fun replaceSignals(regionId: String, items: List<TrafficSignal>) {
        signals.entries.removeIf { it.value.metadata["regionId"] == regionId }
        items.forEach { signal ->
            signals[signal.id] = signal.copy(
                providerId = STATIC_PROVIDER_ID,
                stateSourceType = SignalSourceType.STATIC,
                state = SignalState.UNKNOWN,
                supportsLiveState = false,
                supportsTiming = false,
                supportsGlosa = false,
                metadata = signal.metadata + ("regionId" to regionId),
            )
        }
    }

    fun signalsIn(corridor: RouteSignalCorridor): List<TrafficSignal> =
        signals.values.filter {
            it.latitude in corridor.minLat..corridor.maxLat &&
                it.longitude in corridor.minLng..corridor.maxLng
        }

    companion object {
        const val STATIC_PROVIDER_ID = "static_osm"
    }
}

@Singleton
class StaticSignalProvider @Inject constructor(
    private val store: StaticTrafficSignalStore,
) : TrafficSignalProvider {
    override val providerId: String = StaticTrafficSignalStore.STATIC_PROVIDER_ID

    override suspend fun getSignalsAround(
        route: Route,
        corridor: RouteSignalCorridor,
    ): List<TrafficSignal> = store.signalsIn(corridor)

    override suspend fun getSignalState(signal: TrafficSignal): TrafficSignal = signal.copy(
        state = SignalState.UNKNOWN,
        stateSourceType = SignalSourceType.STATIC,
        lastUpdatedEpochMs = null,
        confidence = signal.confidence.coerceAtMost(0.65f),
    )

    override suspend fun getIntersectionMap(intersectionId: String): IntersectionMap? = null

    override fun getCapabilities(): TrafficSignalProviderCapabilities =
        TrafficSignalProviderCapabilities(
            providerId = providerId,
            capabilities = setOf(TrafficSignalCapability.STATIC),
            endpointStatus = TrafficSignalEndpointStatus.STATIC_ONLY,
            enabled = true,
            freshnessWindowMs = Long.MAX_VALUE,
            priority = 90,
            reliability = 0.6f,
        )
}
