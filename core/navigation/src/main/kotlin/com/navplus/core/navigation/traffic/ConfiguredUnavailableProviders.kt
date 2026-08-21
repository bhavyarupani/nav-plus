package com.navplus.core.navigation.traffic

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IngolstadtSignalProvider @Inject constructor() : UnavailableTrafficSignalProvider(
    providerId = "ingolstadt",
    status = TrafficSignalEndpointStatus.CONFIGURED_BUT_UNAVAILABLE,
    capabilities = setOf(TrafficSignalCapability.PREDICTION, TrafficSignalCapability.GLOSA),
    notes = "No programmatically usable public Ingolstadt signal endpoint is configured. Enable only after endpoint, format, license, and attribution are known.",
)

@Singleton
class TrafficpilotProvider @Inject constructor() : UnavailableTrafficSignalProvider(
    providerId = "trafficpilot",
    status = TrafficSignalEndpointStatus.LIVE_REQUIRES_ACCESS,
    capabilities = setOf(TrafficSignalCapability.PREDICTION, TrafficSignalCapability.GLOSA),
    notes = "Trafficpilot adapter is disabled until a legitimate endpoint and credentials/token are configured. No scraping or reverse-engineering is used.",
)

@Singleton
class Signal2XProvider @Inject constructor() : UnavailableTrafficSignalProvider(
    providerId = "signal2x",
    status = TrafficSignalEndpointStatus.PREDICTION_SERVICE,
    capabilities = setOf(TrafficSignalCapability.PREDICTION, TrafficSignalCapability.GLOSA),
    notes = "Signal2X/Yunex-style adapter is disabled until legitimate API/data access exists.",
)

@Singleton
class CITSSignalProvider @Inject constructor() : UnavailableTrafficSignalProvider(
    providerId = "cits",
    status = TrafficSignalEndpointStatus.CITS_ONLY,
    capabilities = setOf(
        TrafficSignalCapability.CITS,
        TrafficSignalCapability.MAPEM,
        TrafficSignalCapability.SPATEM,
        TrafficSignalCapability.GLOSA,
    ),
    notes = "Generic C-ITS provider is transport-ready by contract but disabled until HTTP, MQTT, WebSocket, cloud gateway, or vehicle/V2X hardware transport is configured.",
)

interface CITSTransport {
    suspend fun messages(corridor: RouteSignalCorridor): List<ByteArray>
}

interface CITSMessageParser {
    fun parseMapem(payload: ByteArray): IntersectionMap?
    fun parseSpatem(payload: ByteArray): List<TrafficSignal>
}
