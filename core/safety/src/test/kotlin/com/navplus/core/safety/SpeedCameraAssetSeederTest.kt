package com.navplus.core.safety

import com.navplus.core.safety.model.CameraType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedCameraAssetSeederTest {
    @Test
    fun `parser imports Germany speed camera GeoJSON points`() {
        val cameras = parseGermanyGeoJson(
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "478454",
                  "geometry": {
                    "type": "Point",
                    "coordinates": [7.5600518, 51.917661]
                  },
                  "properties": {
                    "id": "478454",
                    "camera_type": "fixed",
                    "speed_limit_kmh": 80,
                    "country_code": "DE"
                  }
                },
                {
                  "type": "Feature",
                  "id": "13290318952",
                  "geometry": {
                    "type": "Point",
                    "coordinates": [9.4781396, 52.1636145]
                  },
                  "properties": {
                    "id": "13290318952",
                    "camera_type": "red_light",
                    "speed_limit_kmh": null,
                    "country_code": "DE"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, cameras.size)
        assertEquals("seed_de_478454", cameras[0].id)
        assertEquals(51.917661, cameras[0].lat, 0.000001)
        assertEquals(7.5600518, cameras[0].lng, 0.000001)
        assertEquals(CameraType.FIXED_SPEED, cameras[0].type)
        assertEquals(80, cameras[0].speedLimitKph)
        assertEquals("DE", cameras[0].country)
        assertEquals("seed_speedcams_world_de", cameras[0].source)

        assertEquals(CameraType.RED_LIGHT, cameras[1].type)
        assertNull(cameras[1].speedLimitKph)
    }

    @Test
    fun `parser imports rich Germany camera seed records`() {
        val cameras = parseGermanyCameraSeed(
            """
            {
              "metadata": {
                "schema_version": "2.0",
                "record_count": 2
              },
              "cameras": [
                {
                  "id": "osm-1309779839",
                  "latitude": 49.0046462,
                  "longitude": 8.3654276,
                  "camera_type": "fixed",
                  "enforcement_type": "speed",
                  "speed_limit_kmh": null,
                  "direction_deg": 90,
                  "country_code": "DE",
                  "confidence": 0.97,
                  "source_count": 2
                },
                {
                  "id": "official-karlsruhe-1",
                  "latitude": 49.0101,
                  "longitude": 8.4002,
                  "camera_type": "red_light",
                  "enforcement_type": "red_light",
                  "speed_limit_kmh": 50,
                  "country_code": "DE",
                  "confidence": 0.82,
                  "source_count": 1
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, cameras.size)
        assertEquals("seed_de_master_osm-1309779839", cameras[0].id)
        assertEquals(49.0046462, cameras[0].lat, 0.000001)
        assertEquals(8.3654276, cameras[0].lng, 0.000001)
        assertEquals(CameraType.FIXED_SPEED, cameras[0].type)
        assertEquals(90f, cameras[0].directionDeg)
        assertNull(cameras[0].speedLimitKph)
        assertEquals("seed_speedcams_world_de_master_rich", cameras[0].source)
        assertEquals(0.97f, cameras[0].confidence)

        assertEquals(CameraType.RED_LIGHT, cameras[1].type)
        assertEquals(50, cameras[1].speedLimitKph)
    }
}
