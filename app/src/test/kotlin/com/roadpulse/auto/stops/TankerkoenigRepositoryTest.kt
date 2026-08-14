package com.roadpulse.auto.stops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TankerkoenigRepositoryTest {
    @Test
    fun `parses open stations with prices`() {
        val json =
            """
            {"ok":true,"stations":[
              {"id":"abc-123","lat":49.1,"lng":9.1,"name":"Aral Tankstelle","brand":"Aral",
               "isOpen":true,"diesel":1.649,"e5":1.749,"e10":1.699}
            ]}
            """.trimIndent()

        val station = TankerkoenigRepository().parse(json).single()

        assertEquals("abc-123", station.id)
        assertEquals("Aral", station.brand)
        assertTrue(station.isOpenNow)
        assertEquals(1.649, station.dieselPrice!!, 0.001)
        assertEquals(1.749, station.e5Price!!, 0.001)
        assertEquals(1.699, station.e10Price!!, 0.001)
    }

    @Test
    fun `treats a missing or null price as unknown rather than zero`() {
        val json =
            """
            {"ok":true,"stations":[
              {"id":"closed-1","lat":49.1,"lng":9.1,"name":"Shell","brand":"Shell",
               "isOpen":false,"diesel":null,"e5":1.799}
            ]}
            """.trimIndent()

        val station = TankerkoenigRepository().parse(json).single()

        assertNull(station.dieselPrice)
        assertEquals(1.799, station.e5Price!!, 0.001)
        assertNull(station.e10Price)
    }

    @Test
    fun `returns nothing when the API reports failure`() {
        val json = """{"ok":false,"message":"invalid apikey","stations":[]}"""

        assertTrue(TankerkoenigRepository().parse(json).isEmpty())
    }
}
