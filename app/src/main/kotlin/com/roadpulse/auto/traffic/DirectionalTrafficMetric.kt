package com.roadpulse.auto.traffic

import kotlin.math.roundToInt

/**
 * A route-direction estimate derived from a real loop-detector measurement.
 * It must not be created from map colour or an incident report.
 */
data class DirectionalTrafficMetric(
    val vehiclesPerHour: Int,
    val averageSpeedKph: Double,
    val horizonKm: Double,
    val estimatedVehiclesInHorizon: Int,
    val measuredAtMillis: Long,
    val direction: String,
) {
    companion object {
        fun fromMeasuredFlow(
            vehiclesPerHour: Int,
            averageSpeedKph: Double,
            horizonKm: Double,
            measuredAtMillis: Long,
            direction: String,
        ): DirectionalTrafficMetric? {
            if (vehiclesPerHour < 0 || averageSpeedKph <= 0.0 || horizonKm <= 0.0) return null
            val vehiclesPerKilometre = vehiclesPerHour / averageSpeedKph
            return DirectionalTrafficMetric(
                vehiclesPerHour = vehiclesPerHour,
                averageSpeedKph = averageSpeedKph,
                horizonKm = horizonKm,
                estimatedVehiclesInHorizon = (vehiclesPerKilometre * horizonKm).roundToInt(),
                measuredAtMillis = measuredAtMillis,
                direction = direction,
            )
        }
    }
}
