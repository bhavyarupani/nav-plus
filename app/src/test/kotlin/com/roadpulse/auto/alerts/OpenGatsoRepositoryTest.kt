package com.roadpulse.auto.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenGatsoRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `haversine distance is approximately correct`() {
        val distance = OpenGatsoRepository.distanceMeters(48.0, 9.0, 48.009, 9.0)

        assertTrue(distance in 995..1_007)
    }

    @Test
    fun `identical points have zero distance`() {
        assertEquals(0, OpenGatsoRepository.distanceMeters(48.0, 9.0, 48.0, 9.0))
    }

    @Test
    fun `nearest search returns enforcement points but not ordinary hazards`() {
        val data = temporaryFolder.newFile("GATSO_ALL.csv")
        data.writeText(
            "9.0000,48.0010,\"max @50\",Camera\n" +
                "9.0000,48.0020,tunnel,Tunnel\n" +
                "9.0000,48.0030,stop,Red light\n",
        )

        val results = OpenGatsoRepository(data).nearestEnforcementLocations(48.0, 9.0)

        assertEquals(2, results.size)
        assertEquals(OpenGatsoPoiType.SPEED_CAMERA, results[0].poi.type)
        assertEquals(OpenGatsoPoiType.RED_LIGHT_CAMERA, results[1].poi.type)
    }

    @Test
    fun `visible bounds search returns every enforcement point inside the viewport`() {
        val data = temporaryFolder.newFile("GATSO_VISIBLE.csv")
        data.writeText(
            "9.0000,48.0010,\"max @50\",Inside one\n" +
                "9.0100,48.0100,\"max @80\",Inside two\n" +
                "9.0200,48.0200,stop,Outside\n" +
                "9.0050,48.0050,tunnel,Not enforcement\n",
        )

        val results =
            OpenGatsoRepository(data).enforcementLocationsInBounds(
                southLatitude = 48.0,
                westLongitude = 8.99,
                northLatitude = 48.015,
                eastLongitude = 9.015,
                referenceLatitude = 48.0,
                referenceLongitude = 9.0,
            )

        assertEquals(2, results.size)
        assertEquals(listOf(50, 80), results.map { it.poi.speedLimitKph })
    }

    @Test
    fun `visible bounds search handles a viewport crossing the date line`() {
        val data = temporaryFolder.newFile("GATSO_DATELINE.csv")
        data.writeText(
            "179.5,10.0,\"max @50\",East side\n" +
                "-179.5,10.0,\"max @60\",West side\n" +
                "0.0,10.0,\"max @70\",Outside\n",
        )

        val results =
            OpenGatsoRepository(data).enforcementLocationsInBounds(
                southLatitude = 9.0,
                westLongitude = 179.0,
                northLatitude = 11.0,
                eastLongitude = -179.0,
                referenceLatitude = 10.0,
                referenceLongitude = 180.0,
            )

        assertEquals(2, results.size)
    }
}
