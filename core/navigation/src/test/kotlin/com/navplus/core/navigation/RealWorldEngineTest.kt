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
        val frame = engine.simulationFrame()
        val types = frame.cues.map { it.type }.toSet()

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
