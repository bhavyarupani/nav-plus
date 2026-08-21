package com.navplus.core.safety

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.Severity
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import com.navplus.core.safety.model.SpeedCameraFetchTile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineScenarioTest {

    @Test
    fun `camera alert uses active route corridor and overspeed severity`() = runTest {
        val camera = camera(
            id = "ahead",
            position = point(2.0),
            direction = 0f,
            speedLimit = 30,
        )
        val engine = SafetyEngine(SpeedCameraRepository(FakeCameraDao(listOf(camera))))
        engine.setRoute(straightRoute())

        engine.updatePosition(position = point(0.0), headingDeg = 0f, speedKph = 55f)

        val alert = engine.alerts.value.single()
        assertEquals("ahead", alert.id)
        assertEquals(Severity.CRITICAL, alert.severity)
        assertEquals(30, alert.speedLimitKph)
        assertTrue(alert.distanceMeters in 1_900.0..2_100.0)
    }

    @Test
    fun `camera alert ignores opposite direction and beyond 3 km`() = runTest {
        val cameras = listOf(
            camera("opposite", point(1.0), direction = 180f, speedLimit = 50),
            camera("too-far", point(3.6), direction = 0f, speedLimit = 50),
        )
        val engine = SafetyEngine(SpeedCameraRepository(FakeCameraDao(cameras)))
        engine.setRoute(straightRoute())

        engine.updatePosition(position = point(0.0), headingDeg = 0f, speedKph = 50f)

        assertTrue(engine.alerts.value.isEmpty())
    }

    private fun straightRoute(): Route {
        val start = point(0.0)
        val end = point(4.0)
        val step = RouteStep(
            instruction = "Continue north",
            maneuver = Maneuver.STRAIGHT,
            distanceMeters = 4_000.0,
            durationSeconds = 240,
            startLocation = start,
            endLocation = end,
            geometry = listOf(start, end),
            speedLimitKph = 50,
        )
        return Route(
            id = "safety",
            waypoints = listOf(start, end),
            geometry = listOf(start, end),
            steps = listOf(step),
            distanceMeters = 4_000.0,
            durationSeconds = 240,
        )
    }

    private fun point(kmNorth: Double): LatLng = LatLng(49.0 + kmNorth * 0.009, 8.0)

    private fun camera(id: String, position: LatLng, direction: Float?, speedLimit: Int) = SpeedCamera(
        id = id,
        lat = position.lat,
        lng = position.lng,
        type = CameraType.FIXED_SPEED,
        directionDeg = direction,
        speedLimitKph = speedLimit,
        country = "DE",
        source = "test",
    )

    private class FakeCameraDao(private val cameras: List<SpeedCamera>) : SpeedCameraDao {
        override suspend fun getCamerasInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double,
        ): List<SpeedCamera> = cameras.filter { it.lat in minLat..maxLat && it.lng in minLng..maxLng }

        override suspend fun upsertAll(cameras: List<SpeedCamera>) = Unit
        override suspend fun deleteOlderThan(timestampMs: Long) = Unit
        override suspend fun count(): Int = cameras.size
        override suspend fun countBySource(source: String): Int = cameras.count { it.source == source }
        override suspend fun deleteBySource(source: String) = Unit
        override suspend fun getFetchTile(tileKey: String): SpeedCameraFetchTile? = null
        override suspend fun upsertFetchTile(tile: SpeedCameraFetchTile) = Unit
    }
}
