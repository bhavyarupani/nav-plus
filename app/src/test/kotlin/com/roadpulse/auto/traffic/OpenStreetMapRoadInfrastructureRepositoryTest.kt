package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStreetMapRoadInfrastructureRepositoryTest {
    @Test
    fun `recognizes German give way junction priority and priority road signs`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":101,"lat":49.1,"lon":9.1,
               "tags":{"traffic_sign":"DE:205"}},
              {"type":"node","id":102,"lat":49.2,"lon":9.2,
               "tags":{"traffic_sign:forward":"DE:301"}},
              {"type":"node","id":103,"lat":49.3,"lon":9.3,
               "tags":{"traffic_sign":"DE:306"}}
            ]}
            """.trimIndent()

        val points = OpenStreetMapRoadInfrastructureRepository().parse(json).points

        assertEquals(RoadInfrastructureType.GIVE_WAY_SIGN, points.single { it.id.endsWith("/101") }.type)
        assertEquals(
            RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
            points.single { it.id.endsWith("/102") }.type,
        )
        assertEquals(
            RoadInfrastructureType.PRIORITY_ROAD_SIGN,
            points.single { it.id.endsWith("/103") }.type,
        )
        assertTrue(points.all(RoadInfrastructurePoint::isJunctionPrioritySign))
    }

    @Test
    fun `parses signals signs directions and nearby Autobahn references`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":1,"lat":49.1,"lon":9.1,
               "tags":{"highway":"traffic_signals","direction":"forward"}},
              {"type":"node","id":2,"lat":49.2,"lon":9.2,
               "tags":{"traffic_sign":"DE:274-50"}},
              {"type":"node","id":4,"lat":49.25,"lon":9.25,
               "tags":{"traffic_sign":"DE:274.2"}},
              {"type":"node","id":5,"lat":49.24,"lon":9.24,
               "tags":{"highway":"crossing","crossing":"traffic_signals"}},
              {"type":"node","id":6,"lat":49.23,"lon":9.23,
               "tags":{"traffic_calming":"table","maxspeed":"30"}},
              {"type":"way","id":3,"center":{"lat":49.3,"lon":9.3},
               "tags":{"highway":"motorway","ref":"A 3; A 81"}}
            ]}
            """.trimIndent()

        val result = OpenStreetMapRoadInfrastructureRepository().parse(json, 123L)

        assertEquals(setOf("A3", "A81"), result.autobahnRefs)
        assertEquals(5, result.points.size)
        assertEquals("Speed limit 50", result.points.single { it.id == "node/2" }.title)
        assertEquals("Speed-limit zone ends", result.points.single { it.id == "node/4" }.title)
        val signal = result.points.single { it.type == RoadInfrastructureType.TRAFFIC_SIGNAL }
        assertTrue(signal.detail.contains("live phase unavailable"))
        assertFalse(signal.hasLiveSignalPhase)
        assertEquals(
            "Pedestrian crossing",
            result.points.single { it.id == "node/5" }.title,
        )
        assertEquals(
            RoadInfrastructureType.TRAFFIC_CALMING,
            result.points.single { it.id == "node/6" }.type,
        )
    }

    @Test
    fun `classifies free paid unknown and inaccessible highway restrooms`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":10,"lat":49.1,"lon":9.1,
               "tags":{"amenity":"toilets","name":"PWC Spessart","fee":"no",
                       "opening_hours":"24/7","wheelchair":"yes"}},
              {"type":"node","id":11,"lat":49.2,"lon":9.2,
               "tags":{"amenity":"toilets","fee":"yes","charge":"1 EUR"}},
              {"type":"node","id":12,"lat":49.3,"lon":9.3,
               "tags":{"amenity":"fuel","toilets":"yes"}},
              {"type":"node","id":13,"lat":49.4,"lon":9.4,
               "tags":{"amenity":"toilets","access":"private","fee":"no"}}
            ]}
            """.trimIndent()

        val facilities = OpenStreetMapRoadInfrastructureRepository().parse(json).facilities

        assertEquals(3, facilities.size)
        assertEquals(
            RestroomFeeStatus.FREE,
            facilities.single { it.id.endsWith("/10") }.restroomFeeStatus,
        )
        assertTrue(facilities.single { it.id.endsWith("/10") }.detail.contains("Hours 24/7"))
        assertEquals(
            RestroomFeeStatus.PAID,
            facilities.single { it.id.endsWith("/11") }.restroomFeeStatus,
        )
        assertTrue(facilities.single { it.id.endsWith("/11") }.detail.contains("1 EUR"))
        assertEquals(
            RestroomFeeStatus.UNKNOWN,
            facilities.single { it.id.endsWith("/12") }.restroomFeeStatus,
        )
    }

    @Test
    fun `preserves mapped incline direction and value in the marker title`() {
        val json =
            """
            {"elements":[
              {"type":"way","id":21,"center":{"lat":49.1,"lon":9.1},
               "tags":{"highway":"primary","incline":"8%"}},
              {"type":"way","id":22,"center":{"lat":49.2,"lon":9.2},
               "tags":{"highway":"primary","incline":"down"}}
            ]}
            """.trimIndent()

        val points = OpenStreetMapRoadInfrastructureRepository().parse(json).points

        assertEquals("Mapped incline 8%", points.single { it.id.endsWith("/21") }.title)
        assertEquals("Mapped downhill grade", points.single { it.id.endsWith("/22") }.title)
    }

    @Test
    fun `classifies ordinary directional conditional and implicit road speed limits`() {
        val json =
            """
            {"elements":[
              {"type":"way","id":31,"center":{"lat":49.1,"lon":9.1},
               "tags":{"highway":"primary","maxspeed":"70"}},
              {"type":"way","id":32,"center":{"lat":49.2,"lon":9.2},
               "tags":{"highway":"primary","maxspeed:forward":"80","maxspeed:backward":"60"}},
              {"type":"way","id":33,"center":{"lat":49.3,"lon":9.3},
               "tags":{"highway":"residential","maxspeed":"50",
                       "maxspeed:conditional":"30 @ (Mo-Fr 07:00-17:00)"}},
              {"type":"way","id":34,"center":{"lat":49.4,"lon":9.4},
               "tags":{"highway":"residential","source:maxspeed":"DE:urban"}},
              {"type":"way","id":35,"center":{"lat":49.5,"lon":9.5},
               "tags":{"highway":"motorway","maxspeed":"none"}}
            ]}
            """.trimIndent()

        val points = OpenStreetMapRoadInfrastructureRepository().parse(json).points

        assertEquals(5, points.count { it.type == RoadInfrastructureType.SPEED_LIMIT_SIGN })
        assertEquals("Speed limit 70", points.single { it.id.endsWith("/31") }.title)
        assertEquals(
            "Directional limits · forward 80 · backward 60",
            points.single { it.id.endsWith("/32") }.title,
        )
        assertEquals("Speed limit 50 · conditional", points.single { it.id.endsWith("/33") }.title)
        assertEquals("Implicit speed limit 50", points.single { it.id.endsWith("/34") }.title)
        assertEquals("No fixed speed limit", points.single { it.id.endsWith("/35") }.title)
        assertTrue(points.single { it.id.endsWith("/33") }.detail.contains("Mo-Fr"))
    }

    @Test
    fun `keeps maxspeed way geometry for coloured road sections`() {
        val json =
            """
            {"elements":[
              {"type":"way","id":41,"center":{"lat":49.1,"lon":9.1},
               "geometry":[{"lat":49.09,"lon":9.09},{"lat":49.10,"lon":9.10}],
               "tags":{"highway":"primary","maxspeed":"70"}},
              {"type":"way","id":42,"center":{"lat":49.2,"lon":9.2},
               "geometry":[{"lat":49.19,"lon":9.19},{"lat":49.20,"lon":9.20}],
               "tags":{"highway":"motorway","maxspeed":"none"}}
            ]}
            """.trimIndent()

        val sections = OpenStreetMapRoadInfrastructureRepository().parse(json).speedLimitSections

        assertEquals(2, sections.size)
        assertEquals(70, sections.single { it.id.endsWith("/41") }.speedLimitKph)
        assertEquals(2, sections.single { it.id.endsWith("/41") }.geometry.size)
        assertTrue(sections.single { it.id.endsWith("/42") }.unlimited)
    }

    @Test
    fun `titles motorway junctions with exit number destination or a generic fallback`() {
        val json =
            """
            {"elements":[
              {"type":"node","id":51,"lat":49.1,"lon":9.1,
               "tags":{"highway":"motorway_junction","ref":"23",
                       "destination":"Frankfurt","name":"Dreieck Wiesloch"}},
              {"type":"node","id":52,"lat":49.2,"lon":9.2,
               "tags":{"highway":"motorway_junction",
                       "destination:ref":"A 5","destination:street":"Karlsruhe"}},
              {"type":"node","id":53,"lat":49.3,"lon":9.3,
               "tags":{"highway":"motorway_junction"}}
            ]}
            """.trimIndent()

        val points = OpenStreetMapRoadInfrastructureRepository().parse(json).points

        val withRef = points.single { it.id.endsWith("/51") }
        assertEquals(RoadInfrastructureType.MOTORWAY_JUNCTION, withRef.type)
        assertEquals("Exit 23", withRef.title)
        assertTrue(withRef.detail.contains("toward Frankfurt"))

        val withDestinationRefOnly = points.single { it.id.endsWith("/52") }
        assertTrue(withDestinationRefOnly.detail.contains("toward A 5 · Karlsruhe"))

        val withNeither = points.single { it.id.endsWith("/53") }
        assertEquals("Motorway junction", withNeither.title)
        assertFalse(withNeither.detail.contains("toward"))
    }
}
