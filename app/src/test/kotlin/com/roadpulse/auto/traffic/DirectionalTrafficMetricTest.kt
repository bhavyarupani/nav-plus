package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionalTrafficMetricTest {
    @Test
    fun `estimates vehicles in a directional road horizon from measured flow`() {
        val metric =
            DirectionalTrafficMetric.fromMeasuredFlow(
                vehiclesPerHour = 1_800,
                averageSpeedKph = 90.0,
                horizonKm = 5.0,
                measuredAtMillis = 123L,
                direction = "Würzburg",
            )

        assertEquals(100, metric?.estimatedVehiclesInHorizon)
        assertEquals(1_800, metric?.vehiclesPerHour)
    }

    @Test
    fun `refuses an estimate without a usable measured speed`() {
        assertNull(
            DirectionalTrafficMetric.fromMeasuredFlow(
                vehiclesPerHour = 1_800,
                averageSpeedKph = 0.0,
                horizonKm = 5.0,
                measuredAtMillis = 123L,
                direction = "Würzburg",
            ),
        )
    }
}
