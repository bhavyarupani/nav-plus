package com.roadpulse.auto.signage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignboardRendererTest {
    @Test
    fun `short destination names pass through unchanged`() {
        assertEquals("Esslingen", SignboardRenderer.truncateDestination("Esslingen"))
    }

    @Test
    fun `long destination names are truncated with an ellipsis rather than overflowing`() {
        val long = "Stuttgart-Bad Cannstatt Hauptbahnhof Nord"
        val truncated = SignboardRenderer.truncateDestination(long)
        assertTrue(truncated.length <= 24)
        assertTrue(truncated.endsWith("…"))
    }
}
