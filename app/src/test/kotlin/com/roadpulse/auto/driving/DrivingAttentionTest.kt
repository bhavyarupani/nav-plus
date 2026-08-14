package com.roadpulse.auto.driving

import com.roadpulse.auto.alerts.CameraDataSource
import com.roadpulse.auto.alerts.CameraSourceRecord
import com.roadpulse.auto.alerts.NearbyOpenGatsoPoi
import com.roadpulse.auto.alerts.OpenGatsoPoi
import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingAttentionTest {
    @Test
    fun `critical road control wins over less urgent information`() {
        val result =
            DrivingAttention.build(
                speedLimit = SpeedLimitAheadSummary(50, false, 70, false, 700, 50, 50),
                roadFeatures = listOf(roadFeature(RoadInfrastructureType.STOP_SIGN, 500)),
                cameras = RouteCameraSnapshot(cameras = listOf(camera(300))),
                terrain = null,
            )

        assertEquals(RoadAheadEventType.ROAD_CONTROL, result.primary?.type)
        assertEquals(RoadAheadEventType.CAMERA, result.secondary?.type)
    }

    @Test
    fun `near maneuver suppresses secondary and low priority terrain`() {
        val result =
            DrivingAttention.build(
                speedLimit = SpeedLimitAheadSummary(50, false, 70, false, 900, 60, 54),
                roadFeatures = emptyList(),
                cameras = RouteCameraSnapshot(),
                terrain = null,
                maneuverDistanceMeters = 120,
            )

        assertNull(result.primary)
        assertNull(result.secondary)
    }

    @Test
    fun `camera policy message is never promoted as a driving event`() {
        val result =
            DrivingAttention.build(
                speedLimit = null,
                roadFeatures = emptyList(),
                cameras =
                    RouteCameraSnapshot(
                        blockedReason = "Camera guidance off while driving in Germany",
                    ),
                terrain = null,
            )

        assertEquals("Route clear ahead", result.primaryText)
        assertNull(result.secondaryText)
    }

    private fun roadFeature(
        type: RoadInfrastructureType,
        distance: Int,
    ) = UpcomingRouteRoadFeature(
        point =
            RoadInfrastructurePoint(
                id = type.name,
                coordinate = RoadCoordinate(49.0, 9.0),
                type = type,
                title = "Stop sign",
                detail = "OpenStreetMap",
                direction = null,
                trafficSignCode = null,
            ),
        distanceMeters = distance,
        confidence = RouteMatchConfidence.HIGH,
    )

    private fun camera(distance: Int): UpcomingRouteCamera {
        val item =
            NearbyOpenGatsoPoi(
                poi = OpenGatsoPoi(9.0, 49.0, OpenGatsoPoiType.SPEED_CAMERA, 50, "test"),
                distanceMeters = distance,
                sources = setOf(CameraDataSource.OPENSTREETMAP),
                sourceRecords = listOf(CameraSourceRecord(CameraDataSource.OPENSTREETMAP, "test")),
            )
        return UpcomingRouteCamera("test", item, distance, RouteMatchConfidence.HIGH)
    }
}
