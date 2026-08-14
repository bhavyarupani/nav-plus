package com.roadpulse.auto.driving

import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRoadFeatureAnalyzerTest {
    private val route =
        listOf(
            RoadCoordinate(49.0000, 9.0000),
            RoadCoordinate(49.0300, 9.0000),
        )

    @Test
    fun `keeps only upcoming road features tightly matched to active route`() {
        val behind = point("behind", 49.0005, 9.0000, RoadInfrastructureType.GIVE_WAY_SIGN)
        val giveWay = point("yield", 49.0040, 9.0000, RoadInfrastructureType.GIVE_WAY_SIGN)
        val priorityJunction =
            point(
                "junction",
                49.0080,
                9.0000,
                RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
            )
        val priorityRoad =
            point(
                "priority-road",
                49.0120,
                9.0000,
                RoadInfrastructureType.PRIORITY_ROAD_SIGN,
            )
        val sideRoad = point("side-road", 49.0060, 9.0003, RoadInfrastructureType.GIVE_WAY_SIGN)
        val speedLimit = point("limit", 49.0050, 9.0000, RoadInfrastructureType.SPEED_LIMIT_SIGN)

        val result =
            RouteRoadFeatureAnalyzer.analyze(
                route = route,
                current = RoadCoordinate(49.0010, 9.0000),
                points = listOf(sideRoad, priorityRoad, behind, speedLimit, priorityJunction, giveWay),
            )

        assertEquals(
            listOf("yield", "limit", "junction", "priority-road"),
            result.map { it.point.id },
        )
        assertTrue(result.zipWithNext().all { (first, second) -> first.distanceMeters < second.distanceMeters })
    }

    @Test
    fun `returns no junction controls without a usable active route`() {
        val sign = point("yield", 49.0040, 9.0000, RoadInfrastructureType.GIVE_WAY_SIGN)

        assertTrue(RouteRoadFeatureAnalyzer.analyze(emptyList(), null, listOf(sign)).isEmpty())
    }

    @Test
    fun `rejects a sign mapped for the opposite travel direction`() {
        val northbound =
            point(
                "northbound",
                49.0040,
                9.0000,
                RoadInfrastructureType.STOP_SIGN,
            ).copy(direction = "north")
        val southbound = northbound.copy(id = "southbound", direction = "south")

        val result =
            RouteRoadFeatureAnalyzer.analyze(
                route = route,
                current = RoadCoordinate(49.0010, 9.0000),
                points = listOf(southbound, northbound),
            )

        assertEquals(listOf("northbound"), result.map { it.point.id })
        assertEquals(RouteMatchConfidence.HIGH, result.single().confidence)
    }

    @Test
    fun `includes varied road ahead features but keeps slope out of map guidance`() {
        val features =
            listOf(
                point("signal", 49.0030, 9.0000, RoadInfrastructureType.TRAFFIC_SIGNAL),
                point("stop", 49.0040, 9.0000, RoadInfrastructureType.STOP_SIGN),
                point("pedestrian", 49.0050, 9.0000, RoadInfrastructureType.PEDESTRIAN_CROSSING),
                point("bridge", 49.0060, 9.0000, RoadInfrastructureType.BRIDGE),
                point("tunnel", 49.0070, 9.0000, RoadInfrastructureType.TUNNEL),
                point("slope", 49.0080, 9.0000, RoadInfrastructureType.STEEP_GRADE),
            )

        val result =
            RouteRoadFeatureAnalyzer.analyze(
                route = route,
                current = RoadCoordinate(49.0010, 9.0000),
                points = features,
            )

        assertEquals(
            listOf("signal", "stop", "pedestrian", "bridge", "tunnel"),
            result.map { it.point.id },
        )
    }

    private fun point(
        id: String,
        latitude: Double,
        longitude: Double,
        type: RoadInfrastructureType,
    ) = RoadInfrastructurePoint(
        id = id,
        coordinate = RoadCoordinate(latitude, longitude),
        type = type,
        title = id,
        detail = "OpenStreetMap",
        direction = null,
        trafficSignCode = null,
    )
}
