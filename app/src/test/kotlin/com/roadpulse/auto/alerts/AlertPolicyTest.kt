package com.roadpulse.auto.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private val camera =
        RoadAlert(
            id = "camera-1",
            type = AlertType.SPEED_CAMERA,
            title = "Camera",
            detail = "Demo",
            distanceMeters = 500,
            isSimulated = true,
        )

    private val roadHazard = camera.copy(type = AlertType.ROAD_HAZARD)

    @Test
    fun `camera warnings are hidden while driving in Germany`() {
        assertFalse(AlertPolicy.mayShowWhileDriving(camera, "DE"))
        assertFalse(AlertPolicy.mayShowWhileDriving(camera, "de"))
    }

    @Test
    fun `ordinary road hazards remain visible in Germany`() {
        assertTrue(AlertPolicy.mayShowWhileDriving(roadHazard, "DE"))
    }

    @Test
    fun `camera policy can be enabled only after country-specific review`() {
        assertTrue(AlertPolicy.mayShowWhileDriving(camera, "FR"))
    }

    @Test
    fun `camera can be inspected while parked in Germany`() {
        assertTrue(AlertPolicy.mayShow(camera, "DE", AlertVisibilityMode.PARKED_PLANNING))
        assertFalse(AlertPolicy.mayShow(camera, "DE", AlertVisibilityMode.ACTIVE_DRIVING))
    }

    @Test
    fun `active camera display fails closed when country is unknown`() {
        val poi =
            OpenGatsoPoi(
                longitude = 9.0,
                latitude = 49.0,
                type = OpenGatsoPoiType.SPEED_CAMERA,
                speedLimitKph = 50,
                description = "",
            )

        assertFalse(
            AlertPolicy.mayShowOpenGatsoPoi(poi, "", AlertVisibilityMode.ACTIVE_DRIVING),
        )
        assertTrue(
            AlertPolicy.mayShowOpenGatsoPoi(poi, "DE", AlertVisibilityMode.PARKED_PLANNING),
        )
    }
}
