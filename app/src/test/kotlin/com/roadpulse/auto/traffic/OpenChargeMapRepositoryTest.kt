package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenChargeMapRepositoryTest {
    @Test
    fun `parses a charger with its highest-power connection`() {
        val json =
            """
            [{"ID":98765,"AddressInfo":{"Title":"Rasthof Nord","AddressLine1":"A8, Km 12",
              "Latitude":49.1,"Longitude":9.1},
              "Connections":[{"PowerKW":22},{"PowerKW":150}],
              "StatusType":{"IsOperational":true}}]
            """.trimIndent()

        val facility = OpenChargeMapRepository().parse(json).single()

        assertEquals("openchargemap/98765", facility.id)
        assertEquals(RoadFacilityType.CHARGING, facility.type)
        assertEquals("Rasthof Nord", facility.title)
        assertEquals(150, facility.maximumChargingPowerKw)
        assertTrue(facility.detail.contains("150"))
    }

    @Test
    fun `falls back to operator name and flags non-operational chargers`() {
        val json =
            """
            [{"ID":1,"AddressInfo":{"Latitude":49.2,"Longitude":9.2},
              "OperatorInfo":{"Title":"Ionity"},
              "Connections":[],
              "StatusType":{"IsOperational":false}}]
            """.trimIndent()

        val facility = OpenChargeMapRepository().parse(json).single()

        assertEquals("Ionity", facility.title)
        assertNull(facility.maximumChargingPowerKw)
        assertTrue(facility.detail.contains("non-operational"))
    }

    @Test
    fun `skips entries missing coordinates`() {
        val json = """[{"ID":2,"AddressInfo":{"Title":"No location"}}]"""

        assertTrue(OpenChargeMapRepository().parse(json).isEmpty())
    }
}
