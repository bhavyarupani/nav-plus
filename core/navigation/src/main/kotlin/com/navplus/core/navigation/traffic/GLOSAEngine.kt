package com.navplus.core.navigation.traffic

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor

@Singleton
class GLOSAEngine @Inject constructor() {
    fun advise(
        distanceMeters: Double,
        currentSpeedKph: Float,
        roadSpeedLimitKph: Int?,
        signal: TrafficSignal,
        nowMs: Long,
    ): GlosaAdvice? {
        val limit = roadSpeedLimitKph ?: return null
        val greenWindowStartMs = when (signal.state) {
            SignalState.GREEN -> nowMs
            SignalState.RED, SignalState.RED_YELLOW, SignalState.YELLOW -> signal.predictedChangeEpochMs
            else -> null
        } ?: return null
        val greenWindowEndMs = signal.phaseEndEpochMs ?: return null
        if (signal.confidence < MIN_GLOSA_CONFIDENCE) return null
        if (greenWindowEndMs <= greenWindowStartMs) return null

        val secondsToGreenStart = ((greenWindowStartMs - nowMs).coerceAtLeast(0L)) / 1_000.0
        val secondsToGreenEnd = ((greenWindowEndMs - nowMs).coerceAtLeast(0L)) / 1_000.0
        if (secondsToGreenEnd <= 0.0) return null

        val minSpeedKph = metersPerSecondToKph(distanceMeters / secondsToGreenEnd)
        val maxSpeedKph = if (secondsToGreenStart <= 0.0) {
            limit.toDouble()
        } else {
            metersPerSecondToKph(distanceMeters / secondsToGreenStart)
        }

        val recommendedMin = ceil(minSpeedKph.coerceAtLeast(MIN_RECOMMENDED_SPEED_KPH)).toInt()
        val recommendedMax = floor(maxSpeedKph.coerceAtMost(limit.toDouble())).toInt()
        if (recommendedMin > recommendedMax) return null
        if (recommendedMax > limit) return null
        if (!comfortableSpeedChange(currentSpeedKph, recommendedMin, recommendedMax, distanceMeters)) return null

        return GlosaAdvice(
            recommendedSpeedMinKph = recommendedMin,
            recommendedSpeedMaxKph = recommendedMax,
            likelyStateOnArrival = SignalState.GREEN,
            confidence = signal.confidence,
        )
    }

    private fun comfortableSpeedChange(
        currentSpeedKph: Float,
        recommendedMin: Int,
        recommendedMax: Int,
        distanceMeters: Double,
    ): Boolean {
        if (distanceMeters < 40.0) return false
        val target = recommendedMax.coerceAtLeast(recommendedMin).toFloat()
        val delta = target - currentSpeedKph
        val allowedDelta = if (delta >= 0) 18f else 25f
        return kotlin.math.abs(delta) <= allowedDelta || distanceMeters > 250.0
    }

    private fun metersPerSecondToKph(mps: Double): Double = mps * 3.6

    companion object {
        private const val MIN_GLOSA_CONFIDENCE = 0.72f
        private const val MIN_RECOMMENDED_SPEED_KPH = 8.0
    }
}
