package com.roadpulse.auto.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class OfficialCameraDataParserTest {
    @Test
    fun `parses every useful field from French official CSV`() {
        val csv =
            "Numéro;Type;Date;VMA;Latitude;Longitude\n" +
                "12001;ETVM;31/10/2011 00:00;70;+45.361;+4.2526\n"

        val item = OfficialCameraDataParser.parseFrance(StringReader(csv)).single()

        assertEquals(OpenGatsoPoiType.AVERAGE_SPEED_CAMERA, item.poi.type)
        assertEquals(70, item.poi.speedLimitKph)
        assertEquals("12001", item.sourceRecord.sourceId)
        assertEquals("ETVM", item.sourceRecord.rawType)
        assertEquals("31/10/2011 00:00", item.sourceRecord.installedDate)
    }

    @Test
    fun `parses Brussels identity road direction area date and active state`() {
        val json =
            """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","id":"speedcameras.1",
              "geometry":{"type":"Point","coordinates":[4.3,50.8]},
              "properties":{"radar_id":"B-1","radar_type":3,"street_fr":"Rue Test",
                "mu_fr":"Bruxelles","descr_fr":"Crossing","direction_fr":"north",
                "date_installation":"2021-03-15","active":false}
            }]}
            """.trimIndent()

        val item = OfficialCameraDataParser.parseBrussels(json).single()

        assertEquals("B-1", item.sourceRecord.sourceId)
        assertEquals("Rue Test", item.sourceRecord.roadName)
        assertEquals("north", item.sourceRecord.direction)
        assertEquals("Bruxelles", item.sourceRecord.areaName)
        assertEquals("Crossing", item.sourceRecord.locationDescription)
        assertEquals("2021-03-15", item.sourceRecord.installedDate)
        assertFalse(item.sourceRecord.active!!)
    }

    @Test
    fun `parses Luxembourg road both directions and year`() {
        val json =
            """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","geometry":{"type":"Point","coordinates":[6.12,49.61]},
              "properties":{"ID":"68","TRANCON":"N12","DIR":"North","DIR_":"South","YEAR":"2021"}
            }]}
            """.trimIndent()

        val item = OfficialCameraDataParser.parseLuxembourg(json).single()

        assertEquals("68", item.sourceRecord.sourceId)
        assertEquals("N12", item.sourceRecord.roadName)
        assertEquals("North ↔ South", item.sourceRecord.direction)
        assertEquals("2021", item.sourceRecord.installedDate)
        assertNull(item.poi.speedLimitKph)
    }
}
