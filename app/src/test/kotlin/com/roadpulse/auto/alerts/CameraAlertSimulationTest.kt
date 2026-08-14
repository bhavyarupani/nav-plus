package com.roadpulse.auto.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAlertSimulationTest {
    @Test
    fun `test window expires`() {
        assertTrue(CameraAlertSimulation.isWithinTestWindow(1_000L, 2_000L))
        assertFalse(CameraAlertSimulation.isWithinTestWindow(2_000L, 2_000L))
    }

    @Test
    fun `rejects an implausibly long window`() {
        val elevenMinutes = 11 * 60_000L
        assertFalse(CameraAlertSimulation.isWithinTestWindow(0L, elevenMinutes))
    }
}
