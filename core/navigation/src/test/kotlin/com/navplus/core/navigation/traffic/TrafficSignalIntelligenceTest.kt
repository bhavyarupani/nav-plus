package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.Lane
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficSignalIntelligenceTest {
    private val matcher = SignalMatcher()

    @Test
    fun `opposite-direction signal ignored`() {
        val route = straightRoute()
        val signal = signal(id = "opposite", position = point(0.5), bearing = 180f)

        val matches = matcher.matchSignals(route, 0.0, listOf(signal))

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `crossing-road signal ignored`() {
        val route = straightRoute()
        val signal = signal(id = "crossing", position = LatLng(point(0.5).lat, point(0.5).lng), bearing = 90f)

        val matches = matcher.matchSignals(route, 0.0, listOf(signal))

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `correct turn signal selected`() {
        val route = turnRoute(Maneuver.TURN_LEFT, LaneDirection.LEFT)
        val left = signal("left-signal", point(0.5), movement = SignalMovement.LEFT)
        val right = signal("right-signal", point(0.5), movement = SignalMovement.RIGHT)

        val matches = matcher.matchSignals(route, 0.0, listOf(left, right))

        assertEquals(listOf("left-signal"), matches.map { it.signal.id })
    }

    @Test
    fun `stale live state degrades to static fallback`() {
        val now = 20_000L
        val live = signal("stale", point(0.5)).copy(
            state = SignalState.RED,
            stateSourceType = SignalSourceType.LIVE,
            lastUpdatedEpochMs = 0L,
            confidence = 0.9f,
        )
        val capabilities = TrafficSignalProviderCapabilities(
            providerId = "live",
            capabilities = setOf(TrafficSignalCapability.LIVE_STATE),
            endpointStatus = TrafficSignalEndpointStatus.LIVE_OPEN,
            enabled = true,
            freshnessWindowMs = 5_000L,
            priority = 1,
            reliability = 0.9f,
        )

        val degraded = SignalNormalizer().degradeIfStale(live, now, capabilities)

        assertEquals(SignalSourceType.STATIC, degraded.stateSourceType)
        assertEquals(SignalState.UNKNOWN, degraded.state)
    }

    @Test
    fun `static fallback keeps signal without inventing color`() {
        val staticSignal = signal("static", point(0.5)).copy(
            state = SignalState.UNKNOWN,
            stateSourceType = SignalSourceType.STATIC,
            lastUpdatedEpochMs = null,
        )

        val result = SignalNormalizer().degradeIfStale(staticSignal, nowMs = 99_000L, capabilities = null)

        assertEquals(SignalSourceType.STATIC, result.stateSourceType)
        assertEquals(SignalState.UNKNOWN, result.state)
    }

    @Test
    fun `provider outage returns no fake signals`() = runTest {
        val provider = TrafficpilotProvider()

        assertFalse(provider.getCapabilities().enabled)
        assertTrue(provider.getSignalsAround(straightRoute(), corridor()).isEmpty())
    }

    @Test
    fun `prediction output is labelled predicted by caller`() {
        val prediction = SignalPredictionEngine().predict(
            SignalPredictionInput(
                signalId = "p1",
                cycleLengthSeconds = 60,
                phaseOffsetSeconds = 0,
                greenStartSecond = 20,
                greenEndSecond = 40,
                nowEpochMs = 5_000L,
                providerReliability = 0.8f,
            )
        )

        assertEquals(SignalState.RED, prediction.state)
        assertTrue(prediction.confidence < 0.8f)
    }

    @Test
    fun `glosa never exceeds speed limit`() {
        val now = 1_000L
        val advice = GLOSAEngine().advise(
            distanceMeters = 400.0,
            currentSpeedKph = 45f,
            roadSpeedLimitKph = 50,
            signal = signal("timed", point(0.5)).copy(
                state = SignalState.RED,
                stateSourceType = SignalSourceType.PREDICTED,
                predictedChangeEpochMs = now + 30_000L,
                phaseEndEpochMs = now + 45_000L,
                confidence = 0.9f,
                supportsGlosa = true,
            ),
            nowMs = now,
        )

        assertTrue(advice != null)
        assertTrue(advice!!.recommendedSpeedMaxKph <= 50)
    }

    @Test
    fun `multiple provider dedupe prefers fresh live over static`() {
        val static = signal("static", point(0.5)).copy(
            intersectionId = "i1",
            state = SignalState.UNKNOWN,
            stateSourceType = SignalSourceType.STATIC,
            providerId = "static_osm",
            confidence = 0.6f,
        )
        val live = signal("live", point(0.5)).copy(
            intersectionId = "i1",
            state = SignalState.GREEN,
            stateSourceType = SignalSourceType.LIVE,
            providerId = "hamburg_open",
            lastUpdatedEpochMs = 10_000L,
            confidence = 0.9f,
        )
        val caps = mapOf(
            "static_osm" to StaticSignalProvider(StaticTrafficSignalStore()).getCapabilities(),
            "hamburg_open" to TrafficSignalProviderCapabilities(
                providerId = "hamburg_open",
                capabilities = setOf(TrafficSignalCapability.LIVE_STATE),
                endpointStatus = TrafficSignalEndpointStatus.LIVE_OPEN,
                enabled = true,
                freshnessWindowMs = 15_000L,
                priority = 5,
                reliability = 0.9f,
            )
        )

        val merged = SignalNormalizer().mergeDuplicateSignals(listOf(static, live), 11_000L, caps)

        assertEquals(listOf("live"), merged.map { it.id })
    }

    @Test
    fun `route reroute updates applicable signal`() {
        val northRoute = straightRoute()
        val eastRoute = route(listOf(LatLng(49.0, 8.0), LatLng(49.0, 8.01)), Maneuver.STRAIGHT)
        val northSignal = signal("north", point(0.5), bearing = 0f)

        assertTrue(matcher.matchSignals(northRoute, 0.0, listOf(northSignal)).isNotEmpty())
        assertTrue(matcher.matchSignals(eastRoute, 0.0, listOf(northSignal)).isEmpty())
    }

    @Test
    fun `lane matching exposes applicable lane group`() {
        val route = turnRoute(Maneuver.TURN_LEFT, LaneDirection.LEFT)
        val signal = signal("left", point(0.5), movement = SignalMovement.LEFT)

        val match = matcher.matchSignals(route, 0.0, listOf(signal)).single()

        assertEquals("2", match.applicableLaneGroup)
        assertEquals(SignalMovement.LEFT, match.applicableMovement)
    }

    @Test
    fun `signal behind vehicle ignored`() {
        val route = straightRoute()
        val signal = signal("behind", point(0.2), bearing = 0f)

        val matches = matcher.matchSignals(route, currentDistanceFromStartMeters = 400.0, signals = listOf(signal))

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `source registry parses requested candidates`() {
        val registryFile = File("src/main/assets/traffic_signals/source_registry_de.json")
            .takeIf { it.exists() }
            ?: File("core/navigation/src/main/assets/traffic_signals/source_registry_de.json")
        val configs = TrafficSignalSourceRegistry.parseRegistry(registryFile.readText())

        assertTrue(configs.any { it.city == "Hamburg" && it.provider == "hamburg_open" })
        assertTrue(configs.any { it.city == "Ingolstadt" && it.endpointStatus == TrafficSignalEndpointStatus.CONFIGURED_BUT_UNAVAILABLE })
        assertTrue(configs.any { it.provider == "trafficpilot" && !it.enabled })
    }

    private fun straightRoute(): Route = route(
        points = listOf(point(0.0), point(1.0)),
        maneuver = Maneuver.STRAIGHT,
    )

    private fun turnRoute(maneuver: Maneuver, activeDirection: LaneDirection): Route {
        val start = point(0.0)
        val end = point(1.0)
        return Route(
            id = "turn",
            waypoints = listOf(start, end),
            geometry = listOf(start, end),
            steps = listOf(
                RouteStep(
                    instruction = "Turn",
                    maneuver = maneuver,
                    distanceMeters = 1_000.0,
                    durationSeconds = 90,
                    startLocation = start,
                    endLocation = end,
                    geometry = listOf(start, end),
                    laneGuidance = LaneGuidance(
                        lanes = listOf(
                            Lane(listOf(LaneDirection.STRAIGHT), isActive = false),
                            Lane(listOf(activeDirection), isActive = true),
                        ),
                        recommendedIndices = listOf(1),
                    ),
                    speedLimitKph = 50,
                )
            ),
            distanceMeters = 1_000.0,
            durationSeconds = 90,
        )
    }

    private fun route(points: List<LatLng>, maneuver: Maneuver): Route =
        Route(
            id = "r",
            waypoints = listOf(points.first(), points.last()),
            geometry = points,
            steps = listOf(
                RouteStep(
                    instruction = "Continue",
                    maneuver = maneuver,
                    distanceMeters = 1_000.0,
                    durationSeconds = 90,
                    startLocation = points.first(),
                    endLocation = points.last(),
                    geometry = points,
                    speedLimitKph = 50,
                )
            ),
            distanceMeters = 1_000.0,
            durationSeconds = 90,
        )

    private fun point(kmNorth: Double): LatLng = LatLng(49.0 + kmNorth * 0.009, 8.0)

    private fun signal(
        id: String,
        position: LatLng,
        bearing: Float? = 0f,
        movement: SignalMovement? = SignalMovement.STRAIGHT,
    ): TrafficSignal =
        TrafficSignal(
            id = id,
            intersectionId = id,
            latitude = position.lat,
            longitude = position.lng,
            roadEdgeId = null,
            laneIds = emptyList(),
            movement = movement,
            bearing = bearing,
            distanceAlongRoute = null,
            state = SignalState.UNKNOWN,
            stateSourceType = SignalSourceType.STATIC,
            phaseStartEpochMs = null,
            phaseEndEpochMs = null,
            predictedChangeEpochMs = null,
            confidence = 0.7f,
            lastUpdatedEpochMs = null,
            providerId = "test",
            providerSignalId = id,
            supportsLiveState = false,
            supportsTiming = false,
            supportsGlosa = false,
        )

    private fun corridor(): RouteSignalCorridor =
        RouteSignalCorridor(48.9, 49.1, 7.9, 8.1, 3_000.0)
}
