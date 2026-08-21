package com.navplus.core.navigation.traffic

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalNormalizer @Inject constructor() {
    fun mergeDuplicateSignals(
        signals: List<TrafficSignal>,
        nowMs: Long,
        capabilitiesByProvider: Map<String, TrafficSignalProviderCapabilities>,
    ): List<TrafficSignal> =
        signals
            .groupBy { it.intersectionId ?: nearbyKey(it) }
            .mapNotNull { (_, group) -> group.maxWithOrNull(compareBy<TrafficSignal> { signalScore(it, nowMs, capabilitiesByProvider) }) }

    fun degradeIfStale(
        signal: TrafficSignal,
        nowMs: Long,
        capabilities: TrafficSignalProviderCapabilities?,
    ): TrafficSignal {
        if (signal.stateSourceType == SignalSourceType.STATIC) return signal
        val updated = signal.lastUpdatedEpochMs ?: return signal.toStaticFallback()
        val freshness = capabilities?.freshnessWindowMs ?: DEFAULT_LIVE_FRESHNESS_MS
        return if (nowMs - updated > freshness) signal.toStaticFallback() else signal
    }

    private fun TrafficSignal.toStaticFallback(): TrafficSignal =
        copy(
            state = SignalState.UNKNOWN,
            stateSourceType = SignalSourceType.STATIC,
            phaseStartEpochMs = null,
            phaseEndEpochMs = null,
            predictedChangeEpochMs = null,
            confidence = confidence.coerceAtMost(0.5f),
            supportsLiveState = false,
            supportsTiming = false,
            supportsGlosa = false,
        )

    private fun signalScore(
        signal: TrafficSignal,
        nowMs: Long,
        capabilitiesByProvider: Map<String, TrafficSignalProviderCapabilities>,
    ): Double {
        val provider = capabilitiesByProvider[signal.providerId]
        val freshness = provider?.freshnessWindowMs ?: DEFAULT_LIVE_FRESHNESS_MS
        val agePenalty = signal.lastUpdatedEpochMs
            ?.let { ((nowMs - it).coerceAtLeast(0) / freshness.toDouble()).coerceIn(0.0, 4.0) }
            ?: if (signal.stateSourceType == SignalSourceType.STATIC) 0.5 else 4.0
        val sourceRank = when (signal.stateSourceType) {
            SignalSourceType.LIVE -> 40.0
            SignalSourceType.PREDICTED -> 25.0
            SignalSourceType.STATIC -> 5.0
        }
        val providerPriority = provider?.priority ?: 100
        return sourceRank + signal.confidence * 20.0 - agePenalty * 12.0 - providerPriority
    }

    private fun nearbyKey(signal: TrafficSignal): String =
        "${(signal.latitude * 10_000).toInt()}|${(signal.longitude * 10_000).toInt()}"

    companion object {
        const val DEFAULT_LIVE_FRESHNESS_MS = 15_000L
    }
}
