package com.roadpulse.auto.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStreetMapCameraRepositoryTest {
    @Test
    fun `merges duplicate cameras and preserves both source names`() {
        val openGatso = camera(48.0, 9.0, 50, CameraDataSource.OPEN_GATSO)
        val osm = camera(48.0002, 9.0002, 50, CameraDataSource.OPENSTREETMAP)

        val merged = mergeCameraSources(listOf(openGatso), listOf(osm))

        assertEquals(1, merged.size)
        assertEquals(setOf(CameraDataSource.OPEN_GATSO, CameraDataSource.OPENSTREETMAP), merged[0].sources)
    }

    @Test
    fun `keeps distinct nearby cameras`() {
        val first = camera(48.0, 9.0, 50, CameraDataSource.OPEN_GATSO)
        val second = camera(48.002, 9.002, 50, CameraDataSource.OPENSTREETMAP)

        val merged = mergeCameraSources(listOf(first), listOf(second))

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.sources == setOf(CameraDataSource.OPENSTREETMAP) })
    }

    @Test
    fun `does not merge cameras with conflicting speed limits`() {
        val first = camera(48.0, 9.0, 50, CameraDataSource.OPEN_GATSO)
        val second = camera(48.00001, 9.00001, 80, CameraDataSource.OPENSTREETMAP)

        assertEquals(2, mergeCameraSources(listOf(first), listOf(second)).size)
    }

    @Test
    fun `does not merge different IDs from the same source`() {
        val first =
            camera(48.0, 9.0, 50, CameraDataSource.OPENSTREETMAP).copy(
                sourceRecords =
                    listOf(
                        CameraSourceRecord(CameraDataSource.OPENSTREETMAP, sourceId = "node/1"),
                    ),
            )
        val second =
            camera(48.00001, 9.00001, 50, CameraDataSource.OPENSTREETMAP).copy(
                sourceRecords =
                    listOf(
                        CameraSourceRecord(CameraDataSource.OPENSTREETMAP, sourceId = "node/2"),
                    ),
            )

        assertEquals(2, mergeCameraSources(listOf(first), listOf(second)).size)
    }

    @Test
    fun `does not guess that anonymous records from one source are duplicates`() {
        val first = camera(48.0, 9.0, 50, CameraDataSource.OPEN_GATSO)
        val second = camera(48.00001, 9.00001, 50, CameraDataSource.OPEN_GATSO)

        assertEquals(2, mergeCameraSources(listOf(first), listOf(second)).size)
    }

    private fun camera(
        latitude: Double,
        longitude: Double,
        speed: Int,
        source: CameraDataSource,
    ) = NearbyOpenGatsoPoi(
        poi =
            OpenGatsoPoi(
                longitude = longitude,
                latitude = latitude,
                type = OpenGatsoPoiType.SPEED_CAMERA,
                speedLimitKph = speed,
                description = "",
            ),
        distanceMeters = 0,
        sources = setOf(source),
    )
}
