package com.roadpulse.auto.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenGatsoCsvParserTest {
    @Test
    fun `parses a fixed speed camera and its limit`() {
        val poi =
            OpenGatsoCsvParser.parseLine(
                "9.993682,53.551086,\"max @50\",\"Fixed camera, northbound\"",
            )

        requireNotNull(poi)
        assertEquals(OpenGatsoPoiType.SPEED_CAMERA, poi.type)
        assertEquals(50, poi.speedLimitKph)
        assertEquals("Fixed camera, northbound", poi.description)
    }

    @Test
    fun `parses an average speed camera`() {
        val poi = OpenGatsoCsvParser.parseLine("4.1,50.2,\"average @90\",\"Section control\"")

        requireNotNull(poi)
        assertEquals(OpenGatsoPoiType.AVERAGE_SPEED_CAMERA, poi.type)
        assertEquals(90, poi.speedLimitKph)
    }

    @Test
    fun `rejects invalid coordinates`() {
        assertNull(OpenGatsoCsvParser.parseLine("999,50.2,\"max @50\",\"Invalid\""))
    }

    @Test
    fun `classifies red light cameras as enforcement locations`() {
        val poi = requireNotNull(OpenGatsoCsvParser.parseLine("9.1,48.1,stop,Red light"))

        assertEquals(OpenGatsoPoiType.RED_LIGHT_CAMERA, poi.type)
        assertEquals(true, poi.isEnforcementLocation)
    }
}
