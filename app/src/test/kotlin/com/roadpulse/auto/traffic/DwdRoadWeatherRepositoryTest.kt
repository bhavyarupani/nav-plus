package com.roadpulse.auto.traffic

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class DwdRoadWeatherRepositoryTest {
    @Test
    fun `selects nearest station and exposes severe road condition`() {
        val directory = Files.createTempDirectory("roadpulse-dwd-test").toFile()
        val forecast = File(directory, "forecast.csv.bz2")
        val csv =
            """
            ID;Lat;Lon;YYYYMMDDHHmm;TL;TLSTA;RRL1c;RRS1c;RR6;WWL6;WWS3;RRS3c;R650;RC;TS;TD
            202608121200
            far;51.0;10.0;202608121300;12;0;0;0;0;5;0;0;0;1;14;8
            near;49.1;9.1;202608121300;1;0;0.8;0.2;0;80;70;0;0;6;-1;-2
            near;49.1;9.1;202608121400;2;0;0;0;0;10;5;0;0;2;0;-1
            """.trimIndent()
        BZip2CompressorOutputStream(forecast.outputStream()).bufferedWriter().use { it.write(csv) }

        val result =
            DwdRoadWeatherRepository(directory).parseForecast(
                forecast,
                latitude = 49.11,
                longitude = 9.11,
                nowMillis = Instant.parse("2026-08-12T12:30:00Z").toEpochMilli(),
            )

        assertEquals(2, result.forecasts.size)
        assertEquals("near", result.forecasts.first().stationId)
        assertEquals(RoadSurfaceCondition.BLACK_ICE, result.mostSevere?.condition)
        assertEquals(-1.0, result.mostSevere?.surfaceTemperatureC ?: 0.0, 0.01)
        assertTrue(result.stationDistanceMeters < 2_000)
    }

    @Test
    fun `parses warning polygon and validity`() {
        val json =
            """
            {"features":[{
              "id":"warning-1",
              "properties":{
                "EVENT":"Severe thunderstorm","HEADLINE":"Thunderstorm warning",
                "DESCRIPTION":"Heavy rain and hail","SEVERITY":"Severe",
                "ONSET":"2026-08-12T13:00:00Z","EXPIRES":"2026-08-12T15:00:00Z"
              },
              "geometry":{"type":"Polygon","coordinates":[[
                [9.0,49.0],[9.2,49.0],[9.2,49.2],[9.0,49.2],[9.0,49.0]
              ]]}
            }]}
            """.trimIndent()

        val warning =
            DwdRoadWeatherRepository(Files.createTempDirectory("dwd-warning").toFile())
                .parseWarnings(json, 123L)
                .warnings
                .single()

        assertEquals("Severe thunderstorm", warning.event)
        assertEquals("Severe", warning.severity)
        assertTrue(warning.coordinate.latitude in 49.0..49.2)
        assertTrue(warning.coordinate.longitude in 9.0..9.2)
        assertTrue((warning.expiresAtMillis ?: 0L) > (warning.beginsAtMillis ?: 0L))
    }
}
