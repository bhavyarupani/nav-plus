package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutobahnTrafficRepositoryTest {
    @Test
    fun `parses live queue direction delay and start end geometry`() {
        val json =
            """
            {"warning":[{
              "identifier":"queue-1",
              "future":false,
              "isBlocked":"false",
              "title":"A3 | Seligenstadt - Obertshausen",
              "subtitle":" Würzburg -> Frankfurt",
              "startTimestamp":"2026-08-12T16:32:00Z",
              "delayTimeValue":"19",
              "abnormalTrafficType":"QUEUING_TRAFFIC",
              "description":["Beginn: now","Angespannte Verkehrslage","- Im Stillstand"],
              "source":"inrix",
              "geometry":{"type":"LineString","coordinates":[[8.91,50.05],[8.86,50.06]]}
            }]}
            """.trimIndent()

        val event =
            AutobahnTrafficRepository()
                .parse(
                    json,
                    "A3",
                    AutobahnTrafficRepository.Service.WARNING,
                ).single()

        assertEquals(TrafficEventType.QUEUE, event.type)
        assertEquals("Würzburg -> Frankfurt", event.direction)
        assertEquals(19, event.delayMinutes)
        assertEquals(RoadCoordinate(50.05, 8.91), event.start)
        assertEquals(RoadCoordinate(50.06, 8.86), event.end)
        assertTrue(event.detail.contains("Im Stillstand"))
    }

    @Test
    fun `does not show future road events as live`() {
        val json = """{"roadworks":[{
          "identifier":"future-1","future":true,
          "coordinate":{"lat":49.0,"long":9.0}
        }]}"""

        val events =
            AutobahnTrafficRepository().parse(
                json,
                "A3",
                AutobahnTrafficRepository.Service.ROADWORK,
            )

        assertTrue(events.isEmpty())
    }
}
