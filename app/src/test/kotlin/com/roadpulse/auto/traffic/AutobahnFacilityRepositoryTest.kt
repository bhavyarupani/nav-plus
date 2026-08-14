package com.roadpulse.auto.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutobahnFacilityRepositoryTest {
    @Test
    fun `parses parking capacity and amenities`() {
        val json =
            """
            {"parking_lorry":[{
              "identifier":"parking-1","future":false,"title":"Spessart Nord",
              "subtitle":"A3 Richtung Frankfurt",
              "coordinate":{"lat":49.96,"long":9.51},
              "description":["PKW Stellplätze: 16","LKW Stellplätze: 18"],
              "lorryParkingFeatureIcons":[
                {"description":"WC"},{"description":"Tankstelle"},
                {"description":"notAvailable: shower"}
              ]
            }]}
            """.trimIndent()

        val facilities =
            AutobahnFacilityRepository().parse(
                json,
                "A3",
                AutobahnFacilityRepository.Service.PARKING,
            )
        val facility = facilities.single { it.type == RoadFacilityType.PARKING }

        assertEquals(RoadFacilityType.PARKING, facility.type)
        assertEquals(16, facility.carSpaces)
        assertEquals(18, facility.lorrySpaces)
        assertEquals(listOf("WC", "Tankstelle"), facility.amenities)
        assertTrue(facility.detail.contains("LKW Stellplätze"))
        val restroom = facilities.single { it.type == RoadFacilityType.RESTROOM }
        assertEquals(RestroomFeeStatus.UNKNOWN, restroom.restroomFeeStatus)
        assertTrue(restroom.detail.contains("fee not published"))
    }

    @Test
    fun `keeps the highest advertised charging power`() {
        val json =
            """
            {"electric_charging_station":[{
              "identifier":"charger-1","future":false,"title":"Fast charging",
              "coordinate":{"lat":50.1,"long":8.8},
              "description":["CCS 150 kW","CCS 350 kW","Type 2 22 kW"]
            }]}
            """.trimIndent()

        val facility =
            AutobahnFacilityRepository()
                .parse(
                    json,
                    "A3",
                    AutobahnFacilityRepository.Service.CHARGING,
                ).single()

        assertEquals(RoadFacilityType.CHARGING, facility.type)
        assertEquals(350, facility.maximumChargingPowerKw)
    }
}
