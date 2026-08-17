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
    fun `check speed triggers only for near-limit, never over-limit, unknown, or within-limit`() {
        assertTrue(SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceLevel.NEAR_LIMIT))
        assertFalse(SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceLevel.OVER_LIMIT))
        assertFalse(SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceLevel.WITHIN_LIMIT))
        assertFalse(SpeedComplianceAdvisor.shouldShowCheckSpeed(SpeedComplianceLevel.UNKNOWN))
    }
}
