package com.navplus.core.group

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.distanceTo
import com.navplus.core.group.model.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToLong

class ConvoyEngineTest {
    private val engine = ConvoyEngine()

    @Test
    fun `group eta uses slowest of three cars`() {
        val members = listOf(
            GroupMember(id = "you", name = "You", color = "#38BDF8", etaSec = 320),
            GroupMember(id = "mia", name = "Mia", color = "#22C55E", etaSec = 410),
            GroupMember(id = "arun", name = "Arun", color = "#F97316", etaSec = 390),
        )

        assertEquals(410L, engine.groupEtaSec(members))
    }

    @Test
    fun `car on sparse route segment is not marked deviated`() {
        val route = straightSparseRoute()
        val onSegment = route.geometry.first().offsetMeters(north = 900.0, east = 18.0)

        assertFalse(engine.detectDeviation(onSegment, route, thresholdMeters = 50.0))
    }

    @Test
    fun `car far away from route is marked deviated`() {
        val route = straightSparseRoute()
        val farFromSegment = route.geometry.first().offsetMeters(north = 900.0, east = 240.0)

        assertTrue(engine.detectDeviation(farFromSegment, route, thresholdMeters = 80.0))
    }

    @Test
    fun `rejoin info stays finite and useful for off route car`() {
        val route = multiPointRoute()
        val offRoute = route.geometry[2].offsetMeters(north = -70.0, east = 220.0)

        val rejoin = engine.calculateRejoinInfo(offRoute, deviatedSpeedKph = 36f, route)

        assertTrue(rejoin.distanceMeters > 0.0)
        assertTrue(rejoin.distanceMeters < 2_000.0)
        assertTrue(rejoin.etaSec > 0)
    }

    private fun straightSparseRoute(): Route {
        val start = LatLng(48.70480, 9.57800)
        val end = start.offsetMeters(north = 1_800.0, east = 0.0)
        return routeFrom(listOf(start, end))
    }

    private fun multiPointRoute(): Route {
        val start = LatLng(48.70480, 9.57800)
        return routeFrom(
            listOf(
                start,
                start.offsetMeters(north = 350.0, east = 0.0),
                start.offsetMeters(north = 700.0, east = 160.0),
                start.offsetMeters(north = 1_050.0, east = 320.0),
                start.offsetMeters(north = 1_450.0, east = 340.0),
            )
        )
    }

    private fun routeFrom(points: List<LatLng>): Route {
        val distance = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        return Route(
            id = "test-route",
            waypoints = listOf(points.first(), points.last()),
            geometry = points,
            steps = listOf(
                RouteStep(
                    instruction = "Continue",
                    maneuver = Maneuver.STRAIGHT,
                    distanceMeters = distance,
                    durationSeconds = (distance / 13.0).roundToLong(),
                    startLocation = points.first(),
                    endLocation = points.last(),
                    geometry = points,
                )
            ),
            distanceMeters = distance,
            durationSeconds = (distance / 13.0).roundToLong(),
        )
    }
}

private fun LatLng.offsetMeters(north: Double, east: Double): LatLng {
    val latMeters = 111_320.0
    val lngMeters = latMeters * cos(Math.toRadians(lat))
    return LatLng(
        lat = lat + north / latMeters,
        lng = lng + east / lngMeters,
    )
}
