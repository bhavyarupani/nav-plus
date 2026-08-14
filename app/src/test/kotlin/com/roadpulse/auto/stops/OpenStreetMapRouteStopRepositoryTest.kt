package com.roadpulse.auto.stops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenStreetMapRouteStopRepositoryTest {
    @Test
    fun `classifies supermarkets and fuel stations and skips unrelated tags`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":1,"lat":49.1,"lon":9.1,
               "tags":{"shop":"supermarket","brand":"Lidl","opening_hours":"Mo-Sa 08:00-21:00"}},
              {"type":"way","id":2,"center":{"lat":49.2,"lon":9.2},
               "tags":{"amenity":"fuel","name":"Aral"}},
              {"type":"node","id":3,"lat":49.3,"lon":9.3,
               "tags":{"shop":"bakery","name":"Bäckerei Müller"}}
            ]}
            """.trimIndent()

        val candidates = OpenStreetMapRouteStopRepository().parse(json)

        assertEquals(2, candidates.size)
        val supermarket = candidates.single { it.id == "node/1" }
        assertEquals(OsmRouteStopCategory.SUPERMARKET, supermarket.category)
        assertEquals("Lidl", supermarket.name)
        assertEquals("Mo-Sa 08:00-21:00", supermarket.openingHours)

        val fuel = candidates.single { it.id == "way/2" }
        assertEquals(OsmRouteStopCategory.FUEL, fuel.category)
        assertEquals("Aral", fuel.name)
        assertNull(fuel.openingHours)
    }

    @Test
    fun `prefers brand then name then operator for the display name`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":1,"lat":49.1,"lon":9.1,
               "tags":{"amenity":"fuel","operator":"Regional Coop"}}
            ]}
            """.trimIndent()

        val candidate = OpenStreetMapRouteStopRepository().parse(json).single()

        assertEquals("Regional Coop", candidate.name)
    }

    @Test
    fun `deduplicates elements sharing the same osm id`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":1,"lat":49.1,"lon":9.1,"tags":{"shop":"supermarket","name":"Netto"}},
              {"type":"node","id":1,"lat":49.1,"lon":9.1,"tags":{"shop":"supermarket","name":"Netto"}}
            ]}
            """.trimIndent()

        assertEquals(1, OpenStreetMapRouteStopRepository().parse(json).size)
    }
}
