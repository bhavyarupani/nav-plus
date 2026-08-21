package com.navplus.core.navigation

import com.navplus.core.common.model.Lane
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.safety.SpeedCameraDao
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import com.navplus.core.safety.model.SpeedCameraFetchTile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingScenarioLookaheadTest {

    @Test
    fun `lookahead exposes driving situations in next 3 km only`() = runTest {
        val route = testRoute()
        val cameras = listOf(
            camera("near-camera", route.geometry[2], speedLimit = 30),
            camera("far-camera", point(4.0), speedLimit = 80),
        )
        val engine = LookaheadEngine(FakeCameraDao(cameras))

        val events = engine.eventsAhead(route, currentDistanceFromStartMeters = 0.0)

        assertTrue(events.any { it.type == LookaheadEventType.SPEED_CAMERA && it.title.contains("30") })
        assertTrue(events.any { it.type == LookaheadEventType.SPEED_LIMIT && it.title == "Speed limit 30" })
        assertTrue(events.any { it.type == LookaheadEventType.RESIDENTIAL_ZONE && it.title == "30 zone" })
        assertTrue(events.any { it.type == LookaheadEventType.SCHOOL_ZONE })
        assertTrue(events.any { it.type == LookaheadEventType.TRAFFIC_CALMING })
        assertTrue(events.any { it.type == LookaheadEventType.NOISE_PROTECTION_ZONE })
        assertTrue(events.any { it.type == LookaheadEventType.ROUNDABOUT })
        assertTrue(events.any { it.type == LookaheadEventType.LANE_GUIDANCE && it.title.contains("Use lane 2") })
        assertTrue(events.any { it.type == LookaheadEventType.TUNNEL })
        assertFalse(events.any { it.position == point(4.0) })
        assertTrue(events.all { it.distanceMeters <= LookaheadEngine.LOOKAHEAD_METERS })
    }

    @Test
    fun `lookahead ignores speed cameras that are near but not on the active route`() = runTest {
        val route = testRoute()
        val offRouteCamera = camera(
            id = "parallel-road-camera",
            position = LatLng(point(1.0).lat, point(1.0).lng + 0.001),
            speedLimit = 30,
        )
        val engine = LookaheadEngine(FakeCameraDao(listOf(offRouteCamera), respectBounds = false))

        val events = engine.eventsAhead(route, currentDistanceFromStartMeters = 0.0)

        assertFalse(events.any { it.type == LookaheadEventType.SPEED_CAMERA })
    }

    @Test
    fun `road character analyzer classifies zones and constrained roads`() {
        val route = testRoute()
        val characters = RoadCharacterAnalyzer().analyzeAhead(route, fromStepIndex = 0)

        assertTrue(characters.any { it.type == RoadType.RESIDENTIAL })
        assertTrue(characters.any { it.type == RoadType.SCHOOL_ZONE })
        assertTrue(characters.any { it.type == RoadType.TRAFFIC_CALMING })
    }

    private fun testRoute(): Route {
        val steps = listOf(
            step(
                from = point(0.0),
                to = point(0.5),
                distance = 500.0,
                instruction = "Continue on Main Street",
                speedLimit = 50,
            ),
            step(
                from = point(0.5),
                to = point(1.0),
                distance = 500.0,
                instruction = "Enter residential 30 zone near Schule",
                streetName = "Wohngebiet Schule",
                speedLimit = 30,
            ),
            step(
                from = point(1.0),
                to = point(1.5),
                distance = 500.0,
                instruction = "At the roundabout take the second exit",
                maneuver = Maneuver.ROUNDABOUT_ENTER,
                exitNumber = "2",
                laneGuidance = LaneGuidance(
                    lanes = listOf(
                        Lane(listOf(LaneDirection.LEFT), isActive = false),
                        Lane(listOf(LaneDirection.STRAIGHT), isActive = true),
                        Lane(listOf(LaneDirection.RIGHT), isActive = false),
                    ),
                    recommendedIndices = listOf(1),
                ),
                speedLimit = 30,
            ),
            step(
                from = point(1.5),
                to = point(2.0),
                distance = 500.0,
                instruction = "Traffic calming before noise protection zone",
                streetName = "Verkehrsberuhigt Lärmschutz",
                speedLimit = 30,
            ),
            step(
                from = point(2.0),
                to = point(2.5),
                distance = 500.0,
                instruction = "Enter tunnel",
                maneuver = Maneuver.TUNNEL,
                streetName = "Tunnel",
                speedLimit = 50,
            ),
        )
        return Route(
            id = "scenario",
            waypoints = listOf(steps.first().startLocation, steps.last().endLocation),
            geometry = steps.map { it.startLocation } + steps.last().endLocation,
            steps = steps,
            distanceMeters = steps.sumOf { it.distanceMeters },
            durationSeconds = 240,
        )
    }

    private fun step(
        from: LatLng,
        to: LatLng,
        distance: Double,
        instruction: String,
        maneuver: Maneuver = Maneuver.STRAIGHT,
        streetName: String? = null,
        speedLimit: Int? = null,
        exitNumber: String? = null,
        laneGuidance: LaneGuidance? = null,
    ) = RouteStep(
        instruction = instruction,
        maneuver = maneuver,
        distanceMeters = distance,
        durationSeconds = (distance / 10).toLong(),
        startLocation = from,
        endLocation = to,
        geometry = listOf(from, to),
        laneGuidance = laneGuidance,
        streetName = streetName,
        exitNumber = exitNumber,
        speedLimitKph = speedLimit,
    )

    private fun point(kmNorth: Double): LatLng = LatLng(49.0 + kmNorth * 0.009, 8.0)

    private fun camera(id: String, position: LatLng, speedLimit: Int) = SpeedCamera(
        id = id,
        lat = position.lat,
        lng = position.lng,
        type = CameraType.FIXED_SPEED,
        directionDeg = null,
        speedLimitKph = speedLimit,
        country = "DE",
        source = "test",
    )

    private class FakeCameraDao(
        private val cameras: List<SpeedCamera>,
        private val respectBounds: Boolean = true,
    ) : SpeedCameraDao {
        override suspend fun getCamerasInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double,
        ): List<SpeedCamera> {
            if (!respectBounds) return cameras
            return cameras.filter { it.lat in minLat..maxLat && it.lng in minLng..maxLng }
        }

        override suspend fun upsertAll(cameras: List<SpeedCamera>) = Unit
        override suspend fun deleteOlderThan(timestampMs: Long) = Unit
        override suspend fun count(): Int = cameras.size
        override suspend fun countBySource(source: String): Int = cameras.count { it.source == source }
        override suspend fun deleteBySource(source: String) = Unit
        override suspend fun getFetchTile(tileKey: String): SpeedCameraFetchTile? = null
        override suspend fun upsertFetchTile(tile: SpeedCameraFetchTile) = Unit
    }
}
