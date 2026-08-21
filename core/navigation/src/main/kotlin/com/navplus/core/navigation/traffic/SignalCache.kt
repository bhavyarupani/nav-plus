package com.navplus.core.navigation.traffic

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalCache @Inject constructor() {
    private val signals = LinkedHashMap<String, TrafficSignal>()
    private val intersectionMaps = LinkedHashMap<String, IntersectionMap>()

    fun putSignals(items: List<TrafficSignal>) {
        items.forEach { signals[it.id] = it }
    }

    fun getSignal(id: String): TrafficSignal? = signals[id]

    fun putIntersectionMap(map: IntersectionMap) {
        intersectionMaps[map.intersectionId] = map
    }

    fun getIntersectionMap(intersectionId: String): IntersectionMap? = intersectionMaps[intersectionId]

    fun clearLiveState(nowMs: Long) {
        signals.entries.removeIf { (_, signal) ->
            signal.stateSourceType == SignalSourceType.LIVE &&
                signal.lastUpdatedEpochMs?.let { nowMs - it > LIVE_STATE_HARD_MAX_AGE_MS } == true
        }
    }

    companion object {
        private const val LIVE_STATE_HARD_MAX_AGE_MS = 60_000L
    }
}
