package com.roadpulse.auto.driving

import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.SpeedLimitRoadSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitRouteAnalyzerTest {
    @Test
    fun `finds next mapped limit and calculates time from current speed`() {
        val route = (0..30).map { index -> RoadCoordinate(49.0 + index * .0005, 9.0) }
        val sections =
            listOf(
                SpeedLimitRoadSection(
                    id = "50",
                    geometry = listOf(RoadCoordinate(49.0, 9.0), RoadCoordinate(49.009, 9.0)),
                    speedLimitKph = 50,
                    label = "Speed limit 50",
                ),
                SpeedLimitRoadSection(
                    id = "80",
                    geometry = listOf(RoadCoordinate(49.009, 9.0), RoadCoordinate(49.02, 9.0)),
                    speedLimitKph = 80,
                    label = "Speed limit 80",
                ),
            )

        val summary =
            SpeedLimitRouteAnalyzer.analyze(
                route = route,
                current = route.first(),
                currentSpeedKph = 60.0,
                sections = sections,
            )

        assertNotNull(summary)
        assertEquals(50, summary!!.currentLimitKph)
        assertEquals(80, summary.nextLimitKph)
        assertTrue(summary.distanceMeters in 850..1_150)
        assertTrue(summary.secondsAtCurrentSpeed!! in 50..70)
        assertTrue(summary.compactText().contains("60 km/h"))
    }
}
