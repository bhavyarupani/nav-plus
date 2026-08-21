package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.distanceTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressCalculatorTest {

    @Test
    fun `noisy gps point snaps marker to route and uses route bearing`() {
        val route = route()
        val previous = initialProgress(route)
        val noisyLocation = Location(
            latLng = LatLng(49.009, 8.0001),
            bearingDeg = 270f,
            speedMps = 12f,
            accuracyMeters = 8f,
        )

        val progress = RouteProgressCalculator.updateProgress(previous, noisyLocation, route)

        assertTrue(progress.snappedLocation.distanceTo(LatLng(49.009, 8.0)) < 2.0)
        assertTrue(progress.routeBearingDeg < 5f || progress.routeBearingDeg > 355f)
        assertFalse(progress.isOffRoute)
    }

    @Test
    fun `large route deviation is marked off route`() {
        val route = route()
        val previous = initialProgress(route)
        val farLocation = Location(
            latLng = LatLng(49.009, 8.02),
            bearingDeg = 0f,
            speedMps = 12f,
            accuracyMeters = 5f,
        )

        val progress = RouteProgressCalculator.updateProgress(previous, farLocation, route)

        assertTrue(progress.isOffRoute)
    }

    private fun route(): Route {
        val start = LatLng(49.0, 8.0)
        val mid = LatLng(49.009, 8.0)
        val end = LatLng(49.018, 8.0)
        val steps = listOf(
            step(start, mid, 1_000.0),
            step(mid, end, 1_000.0),
        )
        return Route(
            id = "progress",
            waypoints = listOf(start, end),
            geometry = listOf(start, mid, end),
            steps = steps,
            distanceMeters = 2_000.0,
            durationSeconds = 120,
        )
    }

    private fun step(start: LatLng, end: LatLng, distance: Double) = RouteStep(
        instruction = "Continue",
        maneuver = Maneuver.STRAIGHT,
        distanceMeters = distance,
        durationSeconds = 60,
        startLocation = start,
        endLocation = end,
        geometry = listOf(start, end),
        speedLimitKph = 50,
    )

    private fun initialProgress(route: Route) = RouteProgress(
        route = route,
        currentStepIndex = 0,
        distanceToNextStepMeters = 1_000.0,
        distanceRemainingMeters = 2_000.0,
        durationRemainingSeconds = 120,
        snappedLocation = route.geometry.first(),
        routeBearingDeg = 0f,
        nextManeuver = Maneuver.STRAIGHT,
        nextInstruction = "Continue",
        nextStreetName = null,
        laneGuidance = null,
        signboard = null,
        speedLimitKph = 50,
    )
}
