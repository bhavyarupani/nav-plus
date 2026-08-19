package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutionException

/**
 * Covers [GraphHopperRoutingEngine]'s region-coverage check specifically - the case where origin
 * and destination aren't both within one installed region's bounds fails *before* ever touching
 * the real GraphHopper graph (see `ensureLoaded`'s call site in `calculateRoute`), so these tests
 * never need to load one.
 */
class GraphHopperRoutingEngineTest {
    private fun installStore(): RegionInstallStore = RegionInstallStore(Files.createTempDirectory("gh-routing-test").toFile())

    private fun installRegion(
        store: RegionInstallStore,
        id: String,
        bounds: RegionBounds,
    ) {
        val staging = store.stagingDirFor(id)
        staging.mkdirs()
        File(staging, "manifest.json").writeText(
            """
            {
              "id": "$id", "displayName": "$id",
              "boundsSouth": ${bounds.south}, "boundsWest": ${bounds.west},
              "boundsNorth": ${bounds.north}, "boundsEast": ${bounds.east},
              "installedSizeBytes": 1
            }
            """.trimIndent(),
        )
        store.installFromStaging(id, staging)
    }

    private fun regionNotCoveredStatusOf(future: java.util.concurrent.CompletableFuture<List<Route>>): RouteRequestStatus {
        val error = assertThrows(ExecutionException::class.java) { future.get() }
        return (error.cause as RouteCalculationException).status
    }

    @Test
    fun `calculateRoute fails with REGION_NOT_COVERED when no region is installed`() {
        val engine = GraphHopperRoutingEngine(installStore())

        val future = engine.calculateRoute(RoadCoordinate(53.08, 8.80), RoadCoordinate(53.09, 8.81))

        assertEquals(RouteRequestStatus.REGION_NOT_COVERED, regionNotCoveredStatusOf(future))
    }

    @Test
    fun `calculateRoute fails with REGION_NOT_COVERED when origin and destination are in different installed regions`() {
        val store = installStore()
        installRegion(store, "de-hb", RegionBounds(south = 53.01, west = 8.48, north = 53.61, east = 8.99))
        installRegion(store, "de-bw", RegionBounds(south = 47.5, west = 7.5, north = 49.8, east = 10.5))
        val engine = GraphHopperRoutingEngine(store)

        val future =
            engine.calculateRoute(
                RoadCoordinate(53.08, 8.80), // Bremen
                RoadCoordinate(48.68, 9.65), // Göppingen, in Baden-Württemberg
            )

        assertEquals(RouteRequestStatus.REGION_NOT_COVERED, regionNotCoveredStatusOf(future))
    }

    @Test
    fun `calculateRoute fails with REGION_NOT_COVERED when destination is outside every installed region`() {
        val store = installStore()
        installRegion(store, "de-hb", RegionBounds(south = 53.01, west = 8.48, north = 53.61, east = 8.99))
        val engine = GraphHopperRoutingEngine(store)

        val future =
            engine.calculateRoute(
                RoadCoordinate(53.08, 8.80), // Bremen
                RoadCoordinate(48.68, 9.65), // Göppingen - not installed
            )

        assertEquals(RouteRequestStatus.REGION_NOT_COVERED, regionNotCoveredStatusOf(future))
    }
}
