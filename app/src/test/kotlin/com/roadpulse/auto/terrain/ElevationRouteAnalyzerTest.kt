package com.roadpulse.auto.terrain

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationRouteAnalyzerTest {
    @Test
    fun `samples roughly two kilometres ahead from current route position`() {
        val route = (0..40).map { index -> RoadCoordinate(49.0 + index * 0.001, 9.0) }
        val current = RoadCoordinate(49.0102, 9.0)

        val samples = ElevationRouteAnalyzer.sampleAhead(route, current)

        assertTrue(samples.size in 8..10)
        assertTrue(samples.first().latitude >= 49.010)
        val distance =
            samples.zipWithNext().sumOf { (start, end) ->
                ElevationRouteAnalyzer.distanceMeters(start, end)
            }
        assertTrue(distance in 1_900.0..2_050.0)
    }

    @Test
    fun `summarizes uphill and downhill grades with elevation`() {
        val uphill =
            listOf(
                ElevationSample(RoadCoordinate(49.0, 9.0), 300.0),
                ElevationSample(RoadCoordinate(49.009, 9.0), 350.0),
            )
        val summary = ElevationRouteAnalyzer.summarize(uphill, 123L, false)!!

        assertEquals(300, summary.currentElevationMeters)
        assertEquals(50, summary.elevationChangeMeters)
        assertEquals(SlopeTrend.UPHILL, summary.trend)
        assertTrue(summary.averageGradePercent in 4.8..5.2)
        assertTrue(summary.compactText().contains("↗"))

        val downhill = ElevationRouteAnalyzer.summarize(uphill.reversed(), 123L, true)!!
        assertEquals(SlopeTrend.DOWNHILL, downhill.trend)
        assertTrue(downhill.compactText().contains("↘"))
    }
}
