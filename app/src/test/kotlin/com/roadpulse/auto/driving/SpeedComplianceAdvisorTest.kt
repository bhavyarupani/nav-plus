package com.roadpulse.auto.driving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedComplianceAdvisorTest {
    @Test
    fun `unknown limit does not chime`() {
        val result = SpeedComplianceAdvisor.evaluate(52, null)

        assertEquals(SpeedComplianceLevel.UNKNOWN, result.level)
        assertFalse(result.shouldChime)
    }

    @Test
    fun `speed near limit is amber without chime`() {
        val result = SpeedComplianceAdvisor.evaluate(48, 50)

        assertEquals(SpeedComplianceLevel.NEAR_LIMIT, result.level)
        assertFalse(result.shouldChime)
    }

    @Test
    fun `configured margin triggers chime`() {
        val result = SpeedComplianceAdvisor.evaluate(53, 50)

        assertEquals(SpeedComplianceLevel.OVER_LIMIT, result.level)
        assertTrue(result.shouldChime)
    }

    @Test
    fun `check speed triggers within the camera radius`() {
        assertTrue(SpeedComplianceAdvisor.shouldShowCheckSpeed(0))
        assertTrue(SpeedComplianceAdvisor.shouldShowCheckSpeed(2_999))
        assertTrue(SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceAdvisor.CHECK_SPEED_CAMERA_RADIUS_METERS))
    }

    @Test
    fun `check speed does not trigger beyond the camera radius or with no camera`() {
        assertFalse(
            SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceAdvisor.CHECK_SPEED_CAMERA_RADIUS_METERS + 1),
        )
        assertFalse(SpeedComplianceAdvisor.shouldShowCheckSpeed(null))
    }
}
