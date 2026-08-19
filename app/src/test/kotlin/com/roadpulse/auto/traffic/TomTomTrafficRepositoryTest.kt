package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomTomTrafficRepositoryTest {
    @Test
    fun `parses roadworks incident with description delay and line geometry`() {
        val json =
            """
            {"incidents":[{
              "type":"Feature",
              "id":"12345",
              "geometry":{"type":"LineString","coordinates":[[16.37,48.21],[16.39,48.22]]},
              "properties":{
                "iconCategory":9,
                "delay":600,
                "roadNumbers":["A1"],
                "startTime":"2026-08-12T16:32:00Z",
                "events":[{"description":"Roadworks - one lane closed"}]
              }
            }]}
            """.trimIndent()

        val event = TomTomTrafficRepository().parse(json).single()

        assertEquals(TrafficEventType.ROADWORK, event.type)
        assertEquals("A1", event.roadId)
        assertEquals(10, event.delayMinutes)
        assertEquals("Roadworks - one lane closed", event.detail)
        assertEquals(RoadCoordinate(48.21, 16.37), event.start)
        assertEquals(RoadCoordinate(48.22, 16.39), event.end)
        assertEquals("TomTom", event.source)
    }

    @Test
    fun `classifies jam accident and closure icon categories`() {
        fun eventOfCategory(iconCategory: Int) =
            TomTomTrafficRepository()
                .parse(
                    """{"incidents":[{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,47.0]},
                        "properties":{"iconCategory":$iconCategory}}]}""",
                ).single()

        assertEquals(TrafficEventType.QUEUE, eventOfCategory(6).type)
        assertEquals(TrafficEventType.CLOSURE, eventOfCategory(8).type)
        assertEquals(TrafficEventType.WARNING, eventOfCategory(1).type)
    }

    @Test
    fun `classifies string-named icon categories the same as numeric codes`() {
        val json =
            """{"incidents":[{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,47.0]},
                "properties":{"iconCategory":"ROAD_WORKS"}}]}"""

        val event = TomTomTrafficRepository().parse(json).single()

        assertEquals(TrafficEventType.ROADWORK, event.type)
    }

    @Test
    fun `skips incidents with no usable geometry rather than crashing`() {
        val json = """{"incidents":[{"type":"Feature","properties":{"iconCategory":9}}]}"""

        val events = TomTomTrafficRepository().parse(json)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `malformed json yields no events rather than throwing`() {
        assertTrue(TomTomTrafficRepository().parse("not json at all").isEmpty())
    }
}
