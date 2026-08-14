package com.roadpulse.auto.traffic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadInfrastructureVisibilityTest {
    @Test
    fun `steep grade is travel guidance rather than a map marker`() {
        val grade = point(RoadInfrastructureType.STEEP_GRADE)
        val speedLimit = point(RoadInfrastructureType.SPEED_LIMIT_SIGN)

        assertFalse(grade.shouldDisplayOnMap)
        assertTrue(speedLimit.shouldDisplayOnMap)
    }

    private fun point(type: RoadInfrastructureType) =
        RoadInfrastructurePoint(
            id = type.name,
            coordinate = RoadCoordinate(49.0, 9.0),
            type = type,
            title = type.name,
            detail = "",
            direction = null,
            trafficSignCode = null,
        )
}
