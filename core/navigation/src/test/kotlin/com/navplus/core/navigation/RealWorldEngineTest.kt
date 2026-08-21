package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RealWorldEngineTest {
    private val engine = RealWorldEngine()

    @Test
    fun simulationFrameCoversEveryRealWorldCueType() {
        val origin = LatLng(48.3538, 11.7861)
        val route = airportRoute(origin).copy(hasHighways = true)
        val frames = listOf(
            engine.simulationFrame(),
            engine.frameAhead(
                route = route,
                currentPosition = origin,
                headingDeg = 25f,
                distanceFromStartMeters = 0.0,
                currentSpeedKph = 70f,
                options = RealWorldOptions(),
                environment = clearEvening().copy(
                    now = LocalDateTime.of(2026, 8, 21, 19, 30),
                    cloudCoverPercent = 10,
                    precipitationMm = 0.0,
                    visibilityMeters = 30_000.0,
                ),
                maxCues = RealWorldCueType.entries.size,
            ),
            engine.frameAhead(
                route = route,
                currentPosition = origin,
                headingDeg = 62f,
                distanceFromStartMeters = 0.0,
                currentSpeedKph = 70f,
                options = RealWorldOptions(),
                environment = clearEvening().copy(
                    now = LocalDateTime.of(2026, 8, 21, 22, 30),
                    cloudCoverPercent = 10,
                    precipitationMm = 0.0,
                    visibilityMeters = 30_000.0,
                    moonVisible = true,
                ),
                maxCues = RealWorldCueType.entries.size,
            ),
        )
        val types = frames.flatMap { it.cues }.map { it.type }.toSet()

        RealWorldCueType.entries.forEach { type ->
            assertTrue("Missing $type", type in types)
        }
    }

    @Test
    fun aircraftCueUsesWindshieldOrSideWindowVisibilityModel() {
        val origin = LatLng(48.3538, 11.7861)
        val frame = engine.frameAhead(
            route = airportRoute(origin),
            currentPosition = origin,
            headingDeg = 82f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 80f,
            options = RealWorldOptions(
                airportApproach = false,
                railCrossing = false,
                skyAndLight = false,
                sunGlare = false,
                landmarkGlance = false,
                waterFerryBridge = false,
                wildlifeRisk = false,
                eventCrowd = false,
                roadFeel = false,
            ),
            environment = clearEvening(),
        )

        val aircraft = frame.cues.single { it.type == RealWorldCueType.VISIBLE_AIRCRAFT }
        assertTrue(aircraft.subtitle.contains("window") || aircraft.subtitle.contains("windshield"))
        assertTrue(aircraft.distanceMeters in 5_000.0..20_000.0)
    }

    @Test
    fun masterToggleDisablesAllCues() {
        val frame = engine.frameAhead(
            route = airportRoute(LatLng(48.3538, 11.7861)),
            currentPosition = LatLng(48.3538, 11.7861),
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(enabled = false),
            environment = clearEvening(),
        )

        assertEquals(RealWorldFrame.Empty, frame)
    }

    @Test
    fun weatherEnvironmentDrivesAtmosphere() {
        val frame = engine.frameAhead(
            route = airportRoute(LatLng(48.3538, 11.7861)),
            currentPosition = LatLng(48.3538, 11.7861),
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(precipitationMm = 2.5),
        )

        assertEquals(RealWorldSky.RAIN, frame.atmosphere.sky)
        assertTrue(frame.cues.any { it.type == RealWorldCueType.SKY_LIGHT })
    }

    @Test
    fun strongCrosswindOnExposedRoadCreatesWindCue() {
        val origin = LatLng(48.3538, 11.7861)
        val frame = engine.frameAhead(
            route = airportRoute(origin).copy(hasHighways = true),
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 90f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(windGustKph = 68.0),
            maxCues = RealWorldCueType.entries.size,
        )

        assertTrue(frame.cues.any { it.type == RealWorldCueType.WIND_FLOW })
    }

    @Test
    fun fogAndRouteWeatherCuesUseLowVisibilityAhead() {
        val origin = LatLng(48.3538, 11.7861)
        val frame = engine.frameAhead(
            route = airportRoute(origin),
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 60f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(
                visibilityMeters = 1_500.0,
                fogRisk = true,
                humidityPercent = 96,
            ),
            maxCues = RealWorldCueType.entries.size,
        )

        assertEquals(RealWorldSky.FOG, frame.atmosphere.sky)
        assertTrue(frame.cues.any { it.type == RealWorldCueType.FOG_DEPTH })
        assertTrue(frame.cues.any { it.type == RealWorldCueType.ROUTE_WEATHER })
    }

    @Test
    fun stormEmergencyHazardSurfaceAndArrivalCuesStayRouteRelevant() {
        val origin = LatLng(48.3538, 11.7861)
        val frame = engine.frameAhead(
            route = airportRoute(origin),
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 55f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(
                stormDistanceMeters = 5_200.0,
                stormEtaMinutes = 12,
                emergencyZoneDistanceMeters = 1_900.0,
                hazardSceneDistanceMeters = 2_300.0,
                roadSurface = "wet cobblestone",
                roadSurfaceDistanceMeters = 900.0,
                destinationDistanceMeters = 700.0,
            ),
            maxCues = RealWorldCueType.entries.size,
        )

        assertTrue(frame.cues.any { it.type == RealWorldCueType.STORM_CELL })
        assertTrue(frame.cues.any { it.type == RealWorldCueType.EMERGENCY_VEHICLE })
        assertTrue(frame.cues.any { it.type == RealWorldCueType.HAZARD_SCENE })
        assertTrue(frame.cues.any { it.type == RealWorldCueType.ROAD_SURFACE })
        assertTrue(frame.cues.any { it.type == RealWorldCueType.DESTINATION_ARRIVAL })
    }

    @Test
    fun moonNightSkyOnlyAppearsForClearRuralNight() {
        val origin = LatLng(48.3538, 11.7861)
        val frame = engine.frameAhead(
            route = airportRoute(origin),
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(
                now = LocalDateTime.of(2026, 8, 21, 22, 30),
                cloudCoverPercent = 12,
                moonVisible = true,
            ),
            maxCues = RealWorldCueType.entries.size,
        )

        assertEquals(RealWorldSky.NIGHT, frame.atmosphere.sky)
        assertTrue(frame.cues.any { it.type == RealWorldCueType.MOON_NIGHT_SKY })
    }

    @Test
    fun routePulsePrioritizesEmergencyGpsWeatherThenTraffic() {
        val origin = LatLng(48.3538, 11.7861)
        val route = airportRoute(origin)

        val emergency = engine.frameAhead(
            route = route,
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(
                emergencyZoneDistanceMeters = 2_000.0,
                lowGpsConfidence = true,
                precipitationMm = 1.0,
                trafficDelaySeconds = 700L,
            ),
        )
        val gps = engine.frameAhead(
            route = route,
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(lowGpsConfidence = true, precipitationMm = 1.0),
        )
        val weather = engine.frameAhead(
            route = route,
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(precipitationMm = 1.0, trafficDelaySeconds = 700L),
        )
        val traffic = engine.frameAhead(
            route = route,
            currentPosition = origin,
            headingDeg = 70f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(),
            environment = clearEvening().copy(trafficDelaySeconds = 700L),
        )

        assertEquals(RealWorldRoutePulse.EMERGENCY_AHEAD, emergency.atmosphere.routePulse)
        assertEquals(RealWorldRoutePulse.LOW_GPS_CONFIDENCE, gps.atmosphere.routePulse)
        assertEquals(RealWorldRoutePulse.RAIN_OR_ICE, weather.atmosphere.routePulse)
        assertEquals(RealWorldRoutePulse.HEAVY_CONGESTION, traffic.atmosphere.routePulse)
    }

    @Test
    fun lowSunInForwardViewCreatesGlareCue() {
        val frame = engine.frameAhead(
            route = airportRoute(LatLng(48.3538, 11.7861)),
            currentPosition = LatLng(48.3538, 11.7861),
            headingDeg = 25f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 70f,
            options = RealWorldOptions(
                visibleAircraft = false,
                airportApproach = false,
                railCrossing = false,
                skyAndLight = false,
                landmarkGlance = false,
                waterFerryBridge = false,
                wildlifeRisk = false,
                eventCrowd = false,
                roadFeel = false,
            ),
            environment = RealWorldEnvironment(
                now = LocalDateTime.of(2026, 8, 21, 19, 30),
                visibilityMeters = 25_000.0,
                cloudCoverPercent = 10,
            ),
        )

        assertTrue(frame.cues.any { it.type == RealWorldCueType.SUN_GLARE })
    }

    private fun clearEvening() = RealWorldEnvironment(
        now = LocalDateTime.of(2026, 8, 21, 19, 42),
        visibilityMeters = 30_000.0,
        cloudCoverPercent = 20,
    )

    private fun airportRoute(origin: LatLng) = Route(
        id = "airport-route",
        waypoints = listOf(origin, LatLng(48.4200, 11.9000)),
        geometry = listOf(
            origin,
            LatLng(48.3650, 11.8050),
            LatLng(48.3820, 11.8300),
            LatLng(48.4050, 11.8650),
            LatLng(48.4200, 11.9000),
        ),
        steps = emptyList(),
        distanceMeters = 11_000.0,
        durationSeconds = 720L,
        style = RouteStyle.SCENIC,
    )
}
