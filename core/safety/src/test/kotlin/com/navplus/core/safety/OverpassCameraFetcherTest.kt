package com.navplus.core.safety

import com.navplus.core.safety.model.CameraType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassCameraFetcherTest {
    @Test
    fun `parser includes direct speed camera nodes`() {
        val cameras = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 10,
                  "lat": 48.123,
                  "lon": 9.456,
                  "tags": {
                    "highway": "speed_camera",
                    "maxspeed": "50",
                    "direction": "90"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, cameras.size)
        assertEquals("osm_node_10", cameras.single().id)
        assertEquals(50, cameras.single().speedLimitKph)
        assertEquals(90f, cameras.single().directionDeg)
    }

    @Test
    fun `parser includes enforcement relation device cameras`() {
        val cameras = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": 99,
                  "members": [
                    { "type": "node", "ref": 11, "role": "from" },
                    { "type": "node", "ref": 12, "role": "device" },
                    { "type": "node", "ref": 13, "role": "to" }
                  ],
                  "tags": {
                    "type": "enforcement",
                    "enforcement": "maxspeed",
                    "maxspeed": "80"
                  }
                },
                {
                  "type": "node",
                  "id": 12,
                  "lat": 48.456,
                  "lon": 11.234,
                  "tags": {
                    "direction": "S"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val camera = cameras.single()
        assertEquals("osm_relation_99", camera.id)
        assertEquals(CameraType.FIXED_SPEED, camera.type)
        assertEquals(80, camera.speedLimitKph)
        assertEquals(180f, camera.directionDeg)
        assertEquals(48.456, camera.lat, 0.00001)
        assertEquals(11.234, camera.lng, 0.00001)
    }

    @Test
    fun `parser prefers relation data over duplicate camera node`() {
        val cameras = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 12,
                  "lat": 48.456,
                  "lon": 11.234,
                  "tags": {
                    "highway": "speed_camera"
                  }
                },
                {
                  "type": "relation",
                  "id": 99,
                  "members": [
                    { "type": "node", "ref": 12, "role": "device" }
                  ],
                  "tags": {
                    "type": "enforcement",
                    "enforcement": "traffic_signals;maxspeed",
                    "maxspeed": "30"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, cameras.size)
        assertEquals("osm_relation_99", cameras.single().id)
        assertEquals(CameraType.COMBINED, cameras.single().type)
        assertEquals(30, cameras.single().speedLimitKph)
    }

    @Test
    fun `parser converts mph speed limits to kph`() {
        val camera = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 10,
                  "lat": 48.123,
                  "lon": 9.456,
                  "tags": {
                    "highway": "speed_camera",
                    "maxspeed": "40 mph"
                  }
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertNotNull(camera.speedLimitKph)
        assertTrue(camera.speedLimitKph in 64..65)
    }

    @Test
    fun `parser detects red light camera from OSM note`() {
        val camera = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 1273211935,
                  "lat": 48.1324488,
                  "lon": 11.5847515,
                  "tags": {
                    "highway": "speed_camera",
                    "note": "Rotlichtblitzer"
                  }
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertEquals(CameraType.RED_LIGHT, camera.type)
        assertEquals(null, camera.speedLimitKph)
    }

    @Test
    fun `parser detects red light camera from speed camera traffic signals tag`() {
        val camera = parseCameras(
            """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 2267258127,
                  "lat": 48.1625898,
                  "lon": 11.5748207,
                  "tags": {
                    "highway": "speed_camera",
                    "description": "red light camera",
                    "speed_camera": "traffic_signals"
                  }
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertEquals(CameraType.RED_LIGHT, camera.type)
    }
}
