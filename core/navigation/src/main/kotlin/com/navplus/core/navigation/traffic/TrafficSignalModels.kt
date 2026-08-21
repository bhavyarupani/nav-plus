package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LatLng

data class TrafficSignal(
    val id: String,
    val intersectionId: String?,
    val latitude: Double,
    val longitude: Double,
    val roadEdgeId: String?,
    val laneIds: List<String>,
    val movement: SignalMovement?,
    val bearing: Float?,
    val distanceAlongRoute: Double?,
    val state: SignalState,
    val stateSourceType: SignalSourceType,
    val phaseStartEpochMs: Long?,
    val phaseEndEpochMs: Long?,
    val predictedChangeEpochMs: Long?,
    val confidence: Float,
    val lastUpdatedEpochMs: Long?,
    val providerId: String,
    val providerSignalId: String,
    val supportsLiveState: Boolean,
    val supportsTiming: Boolean,
    val supportsGlosa: Boolean,
    val metadata: Map<String, String> = emptyMap(),
) {
    val position: LatLng get() = LatLng(latitude, longitude)
}

enum class SignalState {
    RED,
    RED_YELLOW,
    YELLOW,
    GREEN,
    FLASHING,
    OFF,
    UNKNOWN,
}

enum class SignalSourceType {
    LIVE,
    PREDICTED,
    STATIC,
}

enum class SignalMovement {
    STRAIGHT,
    LEFT,
    RIGHT,
    U_TURN,
    PEDESTRIAN,
    CYCLE,
    BUS,
    TRAM,
    UNKNOWN,
}

enum class TrafficSignalCapability {
    STATIC,
    LIVE_STATE,
    TIMING,
    MAPEM,
    SPATEM,
    CITS,
    GLOSA,
    PREDICTION,
}

enum class TrafficSignalEndpointStatus {
    LIVE_OPEN,
    LIVE_REQUIRES_ACCESS,
    CITS_ONLY,
    PREDICTION_SERVICE,
    CONFIGURED_BUT_UNAVAILABLE,
    PLANNED,
    STATIC_ONLY,
}

data class TrafficSignalProviderCapabilities(
    val providerId: String,
    val capabilities: Set<TrafficSignalCapability>,
    val endpointStatus: TrafficSignalEndpointStatus,
    val enabled: Boolean,
    val freshnessWindowMs: Long,
    val priority: Int,
    val reliability: Float,
)

data class TrafficSignalSourceConfig(
    val city: String,
    val region: String?,
    val country: String,
    val provider: String,
    val capabilities: Set<TrafficSignalCapability>,
    val endpointStatus: TrafficSignalEndpointStatus,
    val accessType: String,
    val enabled: Boolean,
    val endpoint: String?,
    val notes: String,
    val lastVerified: String,
)

data class RouteSignalCorridor(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val lookaheadMeters: Double,
)

data class IntersectionMap(
    val intersectionId: String,
    val incomingLaneToSignalGroup: Map<String, String>,
    val signalGroupMovement: Map<String, SignalMovement>,
    val outgoingLaneBySignalGroup: Map<String, String>,
)

data class SignalMatch(
    val signal: TrafficSignal,
    val distanceAheadMeters: Double,
    val distanceFromRouteMeters: Double,
    val matchedRouteEdgeIndex: Int,
    val applicableMovement: SignalMovement?,
    val applicableLaneGroup: String?,
) {
    val isSignalRelevantToRoute: Boolean = true
}

data class SignalPredictionInput(
    val signalId: String,
    val cycleLengthSeconds: Int,
    val phaseOffsetSeconds: Int,
    val greenStartSecond: Int,
    val greenEndSecond: Int,
    val nowEpochMs: Long,
    val providerReliability: Float,
)

data class SignalPrediction(
    val state: SignalState,
    val predictedChangeEpochMs: Long?,
    val confidence: Float,
)

data class GlosaAdvice(
    val recommendedSpeedMinKph: Int,
    val recommendedSpeedMaxKph: Int,
    val likelyStateOnArrival: SignalState,
    val confidence: Float,
)

data class TrafficSignalRoadEvent(
    val signalId: String,
    val intersectionId: String?,
    val latitude: Double,
    val longitude: Double,
    val providerId: String,
    val sourceType: SignalSourceType,
    val state: SignalState,
    val sourceTimestampMs: Long?,
    val confidence: Float,
    val distanceMeters: Double,
    val matchedRouteEdgeIndex: Int,
    val matchedLaneGroup: String?,
    val applicableMovement: SignalMovement?,
    val phaseStartEpochMs: Long?,
    val phaseEndEpochMs: Long?,
    val predictedChangeEpochMs: Long?,
    val glosaAdvice: GlosaAdvice?,
)

data class TrafficSignalDebugSnapshot(
    val signalId: String,
    val intersectionId: String?,
    val providerId: String,
    val sourceType: SignalSourceType,
    val state: SignalState,
    val sourceTimestampMs: Long?,
    val ageMs: Long?,
    val confidence: Float,
    val distanceMeters: Double,
    val matchedRouteEdgeIndex: Int,
    val matchedLaneGroup: String?,
    val applicableMovement: SignalMovement?,
    val phaseStartEpochMs: Long?,
    val phaseEndEpochMs: Long?,
    val predictedChangeEpochMs: Long?,
    val glosaAdvice: GlosaAdvice?,
)

fun LaneDirection.toSignalMovement(): SignalMovement = when (this) {
    LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_LEFT -> SignalMovement.LEFT
    LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.SHARP_RIGHT -> SignalMovement.RIGHT
    LaneDirection.U_TURN -> SignalMovement.U_TURN
    else -> SignalMovement.STRAIGHT
}
