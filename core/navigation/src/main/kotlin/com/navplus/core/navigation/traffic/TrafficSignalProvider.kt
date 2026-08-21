package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.Route

interface TrafficSignalProvider {
    val providerId: String

    suspend fun getSignalsAround(
        route: Route,
        corridor: RouteSignalCorridor,
    ): List<TrafficSignal>

    suspend fun getSignalState(signal: TrafficSignal): TrafficSignal?

    suspend fun getIntersectionMap(intersectionId: String): IntersectionMap?

    fun getCapabilities(): TrafficSignalProviderCapabilities
}

abstract class UnavailableTrafficSignalProvider(
    final override val providerId: String,
    private val status: TrafficSignalEndpointStatus,
    private val capabilities: Set<TrafficSignalCapability>,
    private val notes: String,
) : TrafficSignalProvider {
    override suspend fun getSignalsAround(
        route: Route,
        corridor: RouteSignalCorridor,
    ): List<TrafficSignal> = emptyList()

    override suspend fun getSignalState(signal: TrafficSignal): TrafficSignal? = null

    override suspend fun getIntersectionMap(intersectionId: String): IntersectionMap? = null

    override fun getCapabilities(): TrafficSignalProviderCapabilities =
        TrafficSignalProviderCapabilities(
            providerId = providerId,
            capabilities = capabilities,
            endpointStatus = status,
            enabled = false,
            freshnessWindowMs = 0L,
            priority = 100,
            reliability = 0f,
        )

    fun unavailableReason(): String = notes
}
