package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class GraphHopperGuidanceEngineTest {
    /** A straight north-south line along a fixed longitude, ~111m per 0.001 degree latitude. */
    private fun straightRoute(points: Int = 11): Route {
        val geometry = (0 until points).map { RoadCoordinate(53.0000 + it * 0.001, 8.8000) }
        val steps =
            listOf(
                ManeuverStep(ManeuverType.DEPART, "Head north", "Teststrasse", null, 500),
                ManeuverStep(ManeuverType.TURN_RIGHT, "Turn right", "Zielstrasse", null, 500),
                ManeuverStep(ManeuverType.DESTINATION, "Arrive", null, null, 0),
            )
        return Route(
            id = "test-route",
            geometry = geometry,
            distanceMeters = 1000,
            durationSeconds = 100,
            steps = steps,
        )
    }

    private class StubRoutingEngine(
        private val recalculated: Route,
    ) : RoutingEngine {
        var recalculateCallCount = 0

        override fun calculateRoute(
            origin: RoadCoordinate,
            destination: RoadCoordinate,
            waypoints: List<RoadCoordinate>,
            avoidHighways: Boolean,
        ): CompletableFuture<List<Route>> = CompletableFuture.completedFuture(listOf(recalculated))

        override fun recalculateRoute(
            currentLocation: RoadCoordinate,
            destination: RoadCoordinate,
        ): CompletableFuture<Route> {
            recalculateCallCount++
            return CompletableFuture.completedFuture(recalculated)
        }
    }

    @Test
    fun `on route location update reports current step and remaining distance`() {
        val route = straightRoute()
        val engine = GraphHopperGuidanceEngine(StubRoutingEngine(route))
        val states = mutableListOf<GuidanceState>()
        engine.addListener(states::add)
        engine.startGuidance(route)

        // On the route line, ~250m in (a quarter of the way).
        engine.onLocationUpdate(RoadCoordinate(53.0025, 8.8000), speedKph = 50f, bearingDegrees = 0f)

        assertEquals(1, states.size)
        val state = states.first()
        assertFalse(state.isRerouting)
        assertFalse(state.hasArrived)
        assertEquals(ManeuverType.DEPART, state.currentStep?.maneuver)
        assertEquals(ManeuverType.TURN_RIGHT, state.nextStep?.maneuver)
        // Route is ~1112m total (10 segments of ~111.2m each); the fix is ~278m in, so ~834m
        // should remain - computed from the route's own geometry, not route.distanceMeters
        // (deliberately set to a different, unrelated value above to prove it isn't used here).
        assertNotNull(state.distanceToDestinationMeters)
        assertTrue(state.distanceToDestinationMeters!! in 800..870)
    }

    @Test
    fun `arriving within threshold of route end reports arrival`() {
        val route = straightRoute()
        val engine = GraphHopperGuidanceEngine(StubRoutingEngine(route))
        val states = mutableListOf<GuidanceState>()
        engine.addListener(states::add)
        engine.startGuidance(route)

        // Route endpoint is at latitude 53.0000 + 10*0.001 = 53.0100.
        engine.onLocationUpdate(RoadCoordinate(53.0100, 8.8000), speedKph = 5f, bearingDegrees = 0f)

        assertTrue(states.last().hasArrived)
    }

    @Test
    fun `drifting far from the route triggers a reroute after the confirmation window`() {
        val route = straightRoute()
        val recalculated = route.copy(id = "rerouted")
        val stub = StubRoutingEngine(recalculated)
        var fakeNow = 0L
        val engine = GraphHopperGuidanceEngine(stub, nowMillis = { fakeNow })
        val states = mutableListOf<GuidanceState>()
        engine.addListener(states::add)
        engine.startGuidance(route)

        // ~500m off the route line (far beyond the off-route threshold).
        val farAway = RoadCoordinate(53.0050, 8.8100)
        engine.onLocationUpdate(farAway, speedKph = 50f, bearingDegrees = 0f)
        assertFalse("a single off-route fix should not immediately reroute", states.last().isRerouting)
        assertEquals(0, stub.recalculateCallCount)

        // Advance the fake clock past the confirmation window with the driver still off-route.
        fakeNow += 6_000
        engine.onLocationUpdate(farAway, speedKph = 50f, bearingDegrees = 0f)

        assertEquals(1, stub.recalculateCallCount)
        assertTrue("expected isRerouting=true at some point in the emitted states", states.any { it.isRerouting })
        // The stub future is already-completed, so `whenComplete` runs synchronously on this
        // thread and the route swap (isRerouting flipping back to false) is visible immediately.
        assertFalse("expected isRerouting to clear once the reroute resolved", states.last().isRerouting)
    }

    @Test
    fun `stopGuidance clears state so a later update produces nothing`() {
        val route = straightRoute()
        val engine = GraphHopperGuidanceEngine(StubRoutingEngine(route))
        val states = mutableListOf<GuidanceState>()
        engine.startGuidance(route)
        engine.stopGuidance()
        engine.addListener(states::add)

        engine.onLocationUpdate(RoadCoordinate(53.0025, 8.8000), speedKph = 50f, bearingDegrees = 0f)

        assertTrue(states.isEmpty())
    }
}
