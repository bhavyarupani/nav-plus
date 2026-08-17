package com.roadpulse.auto.map

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadCoordinateBoundsTest {
    private val bremen =
        RoadCoordinateBounds(
            northEast = RoadCoordinate(53.20, 8.95),
            southWest = RoadCoordinate(53.00, 8.65),
        )

    @Test
    fun `a coordinate inside the bounds is contained`() {
        assertTrue(bremen.contains(RoadCoordinate(53.0793, 8.8017)))
    }

    @Test
    fun `a coordinate outside the bounds is not contained`() {
        assertFalse(bremen.contains(RoadCoordinate(52.5200, 13.4050))) // Berlin
    }

    @Test
    fun `a coordinate exactly on the boundary is contained`() {
        assertTrue(bremen.contains(RoadCoordinate(53.20, 8.80)))
        assertTrue(bremen.contains(RoadCoordinate(53.00, 8.65)))
    }
}
