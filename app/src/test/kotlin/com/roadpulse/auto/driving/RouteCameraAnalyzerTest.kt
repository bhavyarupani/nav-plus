package com.roadpulse.auto.driving

import com.roadpulse.auto.alerts.CameraDataSource
import com.roadpulse.auto.alerts.CameraSourceRecord
import com.roadpulse.auto.alerts.NearbyOpenGatsoPoi
import com.roadpulse.auto.alerts.OpenGatsoPoi
import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCameraAnalyzerTest {
    private val route =
        listOf(
            RoadCoordinate(49.0000, 9.0000),
            RoadCoordinate(49.0300, 9.0000),
        )

    @Test
    fun `keeps speed and red light cameras ahead on route in driving order`() {
        val behind = camera("behind", 49.0005, 9.0000, OpenGatsoPoiType.SPEED_CAMERA, 30)
        val speed = camera("speed", 49.0050, 9.0000, OpenGatsoPoiType.SPEED_CAMERA, 50)
        val redLight = camera("red", 49.0100, 9.0000, OpenGatsoPoiType.RED_LIGHT_CAMERA, null)
        val sideRoad = camera("side", 49.0070, 9.0005, OpenGatsoPoiType.SPEED_CAMERA, 70)

        val result =
            RouteCameraAnalyzer.analyze(
                route = route,
                current = RoadCoordinate(49.0010, 9.0000),
                cameras = listOf(sideRoad, redLight, behind, speed),
            )

        assertEquals(listOf("OPENSTREETMAP:speed", "OPENSTREETMAP:red"), result.map { it.id })
        assertTrue(result.first().compactText().contains("speed camera · 50 km/h"))
        assertTrue(result.first().compactText().contains("m"))
        assertTrue(result.last().compactText().contains("red-light camera"))
    }

    @Test
    fun `requires a usable route before selecting cameras`() {
        assertTrue(
            RouteCameraAnalyzer
                .analyze(
                    emptyList(),
                    null,
                    listOf(camera("speed", 49.0050, 9.0000, OpenGatsoPoiType.SPEED_CAMERA, 50)),
                ).isEmpty(),
        )
    }

    @Test
    fun `uses mapped camera direction to reject the opposite carriageway`() {
        val northbound =
            camera("north", 49.0050, 9.0000, OpenGatsoPoiType.SPEED_CAMERA, 50)
                .copy(sourceRecords = listOf(CameraSourceRecord(CameraDataSource.OPENSTREETMAP, "north", direction = "0")))
        val southbound =
            camera("south", 49.0060, 9.0000, OpenGatsoPoiType.SPEED_CAMERA, 50)
                .copy(sourceRecords = listOf(CameraSourceRecord(CameraDataSource.OPENSTREETMAP, "south", direction = "180")))

        val result =
            RouteCameraAnalyzer.analyze(
                route = route,
                current = RoadCoordinate(49.0010, 9.0000),
                cameras = listOf(southbound, northbound),
            )

        assertEquals(listOf("OPENSTREETMAP:north"), result.map { it.id })
        assertEquals(RouteMatchConfidence.HIGH, result.single().confidence)
        assertTrue(result.single().compactText().contains("speed camera"))
    }

    private fun camera(
        id: String,
        latitude: Double,
        longitude: Double,
        type: OpenGatsoPoiType,
        speedLimitKph: Int?,
    ) = NearbyOpenGatsoPoi(
        poi =
            OpenGatsoPoi(
                longitude = longitude,
                latitude = latitude,
                type = type,
                speedLimitKph = speedLimitKph,
                description = id,
            ),
        distanceMeters = 0,
        sources = setOf(CameraDataSource.OPENSTREETMAP),
        sourceRecords =
            listOf(
                CameraSourceRecord(
                    source = CameraDataSource.OPENSTREETMAP,
                    sourceId = id,
                ),
            ),
    )
}
