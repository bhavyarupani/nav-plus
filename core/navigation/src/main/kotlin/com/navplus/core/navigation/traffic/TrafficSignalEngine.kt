package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

@Singleton
class TrafficSignalEngine @Inject constructor(
    staticSignalProvider: StaticSignalProvider,
    hamburgSignalProvider: HamburgSignalProvider,
    ingolstadtSignalProvider: IngolstadtSignalProvider,
    trafficpilotProvider: TrafficpilotProvider,
    signal2XProvider: Signal2XProvider,
    citsSignalProvider: CITSSignalProvider,
    private val matcher: SignalMatcher,
    private val normalizer: SignalNormalizer,
    private val cache: SignalCache,
    private val glosaEngine: GLOSAEngine,
) {
    private val providers: List<TrafficSignalProvider> = listOf(
        staticSignalProvider,
        hamburgSignalProvider,
        ingolstadtSignalProvider,
        trafficpilotProvider,
        signal2XProvider,
        citsSignalProvider,
    )

    private var latestDebugSnapshot: TrafficSignalDebugSnapshot? = null

    suspend fun roadEventsAhead(
        route: Route,
        currentDistanceFromStartMeters: Double,
        currentSpeedKph: Float? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<TrafficSignalRoadEvent> = withContext(Dispatchers.Default) {
        val corridor = route.routeCorridor(
            currentDistanceFromStartMeters = currentDistanceFromStartMeters,
            lookaheadMeters = SIGNAL_LOOKAHEAD_METERS,
            paddingMeters = SIGNAL_CORRIDOR_METERS,
        ) ?: return@withContext emptyList()

        cache.clearLiveState(nowMs)

        val capabilitiesByProvider = providers.associate { it.providerId to it.getCapabilities() }
        val providerSignals = providers
            .filter { it.getCapabilities().enabled }
            .flatMap { provider ->
                runCatching { provider.getSignalsAround(route, corridor) }.getOrDefault(emptyList())
            }
        cache.putSignals(providerSignals)

        val freshSignals = providerSignals.map { signal ->
            normalizer.degradeIfStale(signal, nowMs, capabilitiesByProvider[signal.providerId])
        }
        val mergedSignals = normalizer.mergeDuplicateSignals(freshSignals, nowMs, capabilitiesByProvider)
        val matches = matcher.matchSignals(
            route = route,
            currentDistanceFromStartMeters = currentDistanceFromStartMeters,
            signals = mergedSignals,
            corridorMeters = SIGNAL_CORRIDOR_METERS,
            lookaheadMeters = SIGNAL_LOOKAHEAD_METERS,
        )

        val events = matches.take(MAX_TRAFFIC_SIGNAL_EVENTS).map { match ->
            val speedLimit = route.speedLimitNear(match.distanceAheadMeters, currentDistanceFromStartMeters)
            val glosa = if (currentSpeedKph != null && match.signal.supportsGlosa) {
                glosaEngine.advise(
                    distanceMeters = match.distanceAheadMeters,
                    currentSpeedKph = currentSpeedKph,
                    roadSpeedLimitKph = speedLimit,
                    signal = match.signal,
                    nowMs = nowMs,
                )
            } else {
                null
            }
            match.toRoadEvent(glosa)
        }

        latestDebugSnapshot = events.firstOrNull()?.toDebugSnapshot(nowMs)
        events
    }

    fun debugSnapshot(): TrafficSignalDebugSnapshot? = latestDebugSnapshot

    private fun SignalMatch.toRoadEvent(glosa: GlosaAdvice?): TrafficSignalRoadEvent =
        TrafficSignalRoadEvent(
            signalId = signal.id,
            intersectionId = signal.intersectionId,
            latitude = signal.latitude,
            longitude = signal.longitude,
            providerId = signal.providerId,
            sourceType = signal.stateSourceType,
            state = signal.state,
            sourceTimestampMs = signal.lastUpdatedEpochMs,
            confidence = signal.confidence,
            distanceMeters = distanceAheadMeters,
            matchedRouteEdgeIndex = matchedRouteEdgeIndex,
            matchedLaneGroup = applicableLaneGroup,
            applicableMovement = applicableMovement,
            phaseStartEpochMs = signal.phaseStartEpochMs,
            phaseEndEpochMs = signal.phaseEndEpochMs,
            predictedChangeEpochMs = signal.predictedChangeEpochMs,
            glosaAdvice = glosa,
        )

    private fun TrafficSignalRoadEvent.toDebugSnapshot(nowMs: Long): TrafficSignalDebugSnapshot =
        TrafficSignalDebugSnapshot(
            signalId = signalId,
            intersectionId = intersectionId,
            providerId = providerId,
            sourceType = sourceType,
            state = state,
            sourceTimestampMs = sourceTimestampMs,
            ageMs = sourceTimestampMs?.let { nowMs - it },
            confidence = confidence,
            distanceMeters = distanceMeters,
            matchedRouteEdgeIndex = matchedRouteEdgeIndex,
            matchedLaneGroup = matchedLaneGroup,
            applicableMovement = applicableMovement,
            phaseStartEpochMs = phaseStartEpochMs,
            phaseEndEpochMs = phaseEndEpochMs,
            predictedChangeEpochMs = predictedChangeEpochMs,
            glosaAdvice = glosaAdvice,
        )

    private fun Route.routeCorridor(
        currentDistanceFromStartMeters: Double,
        lookaheadMeters: Double,
        paddingMeters: Double,
    ): RouteSignalCorridor? {
        val points = pointsAhead(currentDistanceFromStartMeters, lookaheadMeters)
        if (points.isEmpty()) return null
        val lats = points.map { it.lat }
        val lngs = points.map { it.lng }
        val midLat = (lats.min() + lats.max()) / 2.0
        val latPadding = paddingMeters / 111_000.0
        val lngPadding = paddingMeters / (111_000.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
        return RouteSignalCorridor(
            minLat = lats.min() - latPadding,
            maxLat = lats.max() + latPadding,
            minLng = lngs.min() - lngPadding,
            maxLng = lngs.max() + lngPadding,
            lookaheadMeters = lookaheadMeters,
        )
    }

    private fun Route.pointsAhead(currentDistanceFromStartMeters: Double, lookaheadMeters: Double): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var accumulated = 0.0
        for (index in 1 until geometry.size) {
            val previous = geometry[index - 1]
            val current = geometry[index]
            accumulated += previous.distanceTo(current)
            val distanceAhead = accumulated - currentDistanceFromStartMeters
            if (distanceAhead >= 0.0) points.add(current)
            if (distanceAhead > lookaheadMeters) break
        }
        return points
    }

    private fun Route.speedLimitNear(distanceAheadMeters: Double, currentDistanceFromStartMeters: Double): Int? {
        var accumulated = 0.0
        val targetDistance = currentDistanceFromStartMeters + distanceAheadMeters
        for (step in steps) {
            val next = accumulated + step.distanceMeters
            if (targetDistance <= next) return step.speedLimitKph
            accumulated = next
        }
        return steps.lastOrNull()?.speedLimitKph
    }

    companion object {
        const val SIGNAL_LOOKAHEAD_METERS = 3_000.0
        const val SIGNAL_CORRIDOR_METERS = 35.0
        private const val MAX_TRAFFIC_SIGNAL_EVENTS = 3
    }
}
