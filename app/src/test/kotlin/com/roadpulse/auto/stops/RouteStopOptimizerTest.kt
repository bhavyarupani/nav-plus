package com.roadpulse.auto.stops

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStopOptimizerTest {
    @Test
    fun routeStopModeCyclesFromOffToNowToBestAndBackOff() {
        assertEquals(RouteStopMode.NEED_NOW, RouteStopMode.OFF.next())
        assertEquals(RouteStopMode.BEST_DETOUR, RouteStopMode.NEED_NOW.next())
        assertEquals(RouteStopMode.OFF, RouteStopMode.BEST_DETOUR.next())
    }

    @Test
    fun needNowChoosesFirstOpenStopAheadEvenWhenItsDetourIsLarger() {
        val nearby =
            candidate(
                id = "nearby",
                metersFromRouteOrigin = 3_000,
                addedMeters = 5_000,
            )
        val laterWithBetterDetour =
            candidate(
                id = "later",
                metersFromRouteOrigin = 15_000,
                addedMeters = 500,
            )

        val selected =
            RouteStopOptimizer.selectCandidate(
                listOf(laterWithBetterDetour, nearby),
                RouteStopMode.NEED_NOW,
            )

        assertEquals("nearby", selected?.stopId)
    }

    @Test
    fun bestDetourChoosesSmallestAddedDistanceLaterOnRoute() {
        val nearby =
            candidate(
                id = "nearby",
                metersFromRouteOrigin = 3_000,
                addedMeters = 5_000,
            )
        val laterWithBetterDetour =
            candidate(
                id = "later",
                metersFromRouteOrigin = 15_000,
                addedMeters = 500,
            )

        val selected =
            RouteStopOptimizer.selectCandidate(
                listOf(nearby, laterWithBetterDetour),
                RouteStopMode.BEST_DETOUR,
            )

        assertEquals("later", selected?.stopId)
    }

    @Test
    fun closedStopsAreNotSelectedInEitherMode() {
        val closed =
            candidate(
                id = "closed",
                metersFromRouteOrigin = 1_000,
                addedMeters = 100,
                openAtArrival = false,
            )
        val open =
            candidate(
                id = "open",
                metersFromRouteOrigin = 4_000,
                addedMeters = 700,
            )

        assertEquals(
            "open",
            RouteStopOptimizer.selectCandidate(listOf(closed, open), RouteStopMode.NEED_NOW)?.stopId,
        )
        assertEquals(
            "open",
            RouteStopOptimizer.selectCandidate(listOf(closed, open), RouteStopMode.BEST_DETOUR)?.stopId,
        )
    }

    @Test
    fun supportedSupermarketRecognizesRequestedGermanBrands() {
        listOf(
            "REWE Center",
            "Kaufland Heidelberg",
            "Netto Marken-Discount",
            "Lidl",
            "ALDI SÜD",
        ).forEach { name ->
            assertTrue(name, RouteStopOptimizer.isSupportedSupermarket(name))
        }
        assertFalse(RouteStopOptimizer.isSupportedSupermarket("Unrelated corner shop"))
    }

    private fun candidate(
        id: String,
        metersFromRouteOrigin: Int,
        addedMeters: Int,
        openAtArrival: Boolean = true,
    ) = OptimizedRouteStop(
        category = RouteStopCategory.FUEL,
        selectionMode = RouteStopMode.NEED_NOW,
        stopId = id,
        coordinate = RoadCoordinate(0.0, 0.0),
        title = id,
        metersFromRouteOrigin = metersFromRouteOrigin,
        addedMeters = addedMeters,
        addedSeconds = addedMeters / 10,
        arrivalEpochMillis = 0L,
        openAtArrival = openAtArrival,
    )
}
