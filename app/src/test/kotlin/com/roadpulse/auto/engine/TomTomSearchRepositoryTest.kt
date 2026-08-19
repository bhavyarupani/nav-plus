package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomTomSearchRepositoryTest {
    @Test
    fun `parses a POI result with address and category`() {
        val json =
            """
            {"results":[{
              "position":{"lat":48.21,"lon":16.37},
              "poi":{"name":"IKEA Wien","categories":["furniture_shop"]},
              "address":{"freeformAddress":"Handelskai 1, 1200 Wien"}
            }]}
            """.trimIndent()

        val result = TomTomSearchRepository(null).parse(json).single()

        assertEquals("IKEA Wien", result.title)
        assertEquals("Handelskai 1, 1200 Wien", result.subtitle)
        assertEquals(RoadCoordinate(48.21, 16.37), result.coordinate)
    }

    @Test
    fun `falls back to category when no address is present`() {
        val json =
            """
            {"results":[{
              "position":{"lat":48.21,"lon":16.37},
              "poi":{"name":"Some Cafe","categories":["cafe"]}
            }]}
            """.trimIndent()

        val result = TomTomSearchRepository(null).parse(json).single()

        assertEquals("cafe", result.subtitle)
    }

    @Test
    fun `skips results with no name or address and no usable position`() {
        val json =
            """
            {"results":[
              {"position":{"lat":48.21,"lon":16.37}},
              {"poi":{"name":"No position"}}
            ]}
            """.trimIndent()

        assertTrue(TomTomSearchRepository(null).parse(json).isEmpty())
    }

    @Test
    fun `malformed json yields no results rather than throwing`() {
        assertTrue(TomTomSearchRepository(null).parse("not json at all").isEmpty())
    }

    @Test
    fun `search with no context returns empty rather than crashing`() {
        assertTrue(TomTomSearchRepository(null).search("aldi", null).isEmpty())
    }
}
