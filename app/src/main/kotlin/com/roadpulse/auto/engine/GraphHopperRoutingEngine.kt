package com.roadpulse.auto.engine

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.Instruction
import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * On-device routing via GraphHopper (Apache 2.0, pure JVM - see ZERO_COST_ARCHITECTURE.md for
 * why this was chosen over Valhalla/OSRM). Loads a graph pre-built on a dev machine from a
 * Geofabrik extract and bundled as an asset - `load()` on-device is fast; a full from-scratch
 * import is a build-time step, not something this class ever does at runtime.
 *
 * Pinned to GraphHopper 7.0, not the 8.x-10.x line: from 8.x onward, routing weighting is
 * computed via `CustomModel` expressions compiled at runtime with Janino, which fails on
 * Android's ART/DEX runtime (confirmed via a real on-device `IllegalArgumentException`:
 * "Cannot compile expression... class CustomWeightingHelper could not be found" - a hard
 * incompatibility, not a config issue). 7.0's simple named-weighting API
 * (`Profile.setVehicle/setWeighting`) uses precompiled Java classes (e.g. `FastestWeighting`)
 * instead and has no Janino dependency in its actual code path.
 *
 * This also sidesteps a separate, independent Android incompatibility: GraphHopper's default
 * `RAMDataAccess` storage calls `VarHandle.withInvokeExactBehavior()`, which Android's ART core
 * library does not implement at all (confirmed via a real on-device `NoSuchMethodError`).
 * Verified via `javap` against the real 7.0 jars that neither `RAMDataAccess` nor
 * `MMapDataAccess` in 7.0 call that method, so no MMAP storage override is needed here (unlike
 * the 10.2 attempt this class previously used).
 *
 * Verified end-to-end on a real Bremen OSM extract via a JVM-only test before being written into
 * this Android integration: correct distance/time and real street names for a Bremen
 * Hauptbahnhof -> Bremen Airport route. See ZERO_COST_ARCHITECTURE.md for the full decision
 * record.
 *
 * Region-aware: [regionInstallStore] is the source of truth for which regions are installed and
 * where on disk their `graphhopper/` directory lives (extracted from an APK asset for the bundled
 * Bremen region, from a downloaded `.rpregion` archive otherwise) - this class never touches
 * `context.assets` itself. A route only succeeds if origin and destination fall within the *same*
 * installed region's bounds; cross-region graph stitching isn't attempted (see
 * `RouteRequestStatus.REGION_NOT_COVERED`'s doc comment).
 */
class GraphHopperRoutingEngine(
    private val regionInstallStore: RegionInstallStore,
) : RoutingEngine {
    private val executor = Executors.newSingleThreadExecutor()

    // Accessed only from `executor`'s single thread, so a plain mutable map is safe - at most one
    // loaded GraphHopper graph per installed region for this engine instance's lifetime.
    private val loadedGraphs = mutableMapOf<String, GraphHopper>()

    private fun ensureLoaded(region: InstalledRegion): GraphHopper {
        loadedGraphs[region.id]?.let { return it }
        val graphDir = region.graphHopperDir
        val newHopper =
            GraphHopper().apply {
                graphHopperLocation = graphDir.path
                setProfiles(Profile(PROFILE_NAME).setVehicle(PROFILE_NAME).setWeighting("fastest"))
                chPreparationHandler.setCHProfiles(CHProfile(PROFILE_NAME))
            }
        check(newHopper.load()) { "GraphHopper graph failed to load from $graphDir" }
        loadedGraphs[region.id] = newHopper
        return newHopper
    }

    override fun calculateRoute(
        origin: RoadCoordinate,
        destination: RoadCoordinate,
        waypoints: List<RoadCoordinate>,
        avoidHighways: Boolean,
    ): CompletableFuture<List<Route>> =
        CompletableFuture.supplyAsync({
            val originRegion = regionInstallStore.regionContaining(origin)
            val destinationRegion = regionInstallStore.regionContaining(destination)
            if (originRegion == null || originRegion.id != destinationRegion?.id) {
                val uncovered =
                    listOfNotNull(
                        "origin".takeIf { originRegion == null },
                        "destination".takeIf { destinationRegion == null },
                    )
                val message =
                    if (uncovered.isNotEmpty()) {
                        "No downloaded region covers the ${uncovered.joinToString(" or ")}"
                    } else {
                        "Origin is in ${originRegion?.displayName} but destination is in " +
                            "${destinationRegion?.displayName} - download a region covering both"
                    }
                throw RouteCalculationException(RouteRequestStatus.REGION_NOT_COVERED, message)
            }
            val points = listOf(origin) + waypoints + destination
            val request =
                GHRequest(
                    points.map {
                        com.graphhopper.util.shapes
                            .GHPoint(it.latitude, it.longitude)
                    },
                )
            request.setProfile(PROFILE_NAME)
            val response =
                try {
                    ensureLoaded(originRegion).route(request)
                } catch (e: IllegalStateException) {
                    throw RouteCalculationException(
                        RouteRequestStatus.UNKNOWN_ERROR,
                        "GraphHopper graph failed to load",
                        e,
                    )
                }
            if (response.hasErrors()) {
                throw RouteCalculationException(
                    RouteRequestStatus.NO_ROUTE_FOUND,
                    "GraphHopper routing failed: ${response.errors}",
                )
            }
            response.all.mapIndexed { index, path ->
                val geometry =
                    (0 until path.points.size()).map { i ->
                        RoadCoordinate(path.points.getLat(i), path.points.getLon(i))
                    }
                Route(
                    id = "graphhopper-$index",
                    geometry = geometry,
                    distanceMeters = path.distance.toInt(),
                    durationSeconds = (path.time / 1000).toInt(),
                    isAlternative = index > 0,
                    steps = path.instructions.map(::toManeuverStep),
                )
            }
        }, executor)

    override fun recalculateRoute(
        currentLocation: RoadCoordinate,
        destination: RoadCoordinate,
    ): CompletableFuture<Route> =
        calculateRoute(currentLocation, destination).thenApply { routes ->
            routes.firstOrNull()
                ?: throw RouteCalculationException(RouteRequestStatus.NO_ROUTE_FOUND, "No route found")
        }

    /** Maps a GraphHopper [Instruction] onto this project's provider-independent [ManeuverStep] -
     * see RoutingEngine/GuidanceEngine in NavigationEngine.kt. Public so a future GuidanceEngine
     * implementation can reuse it once turn-by-turn wiring is built. */
    fun toManeuverStep(instruction: Instruction): ManeuverStep =
        ManeuverStep(
            maneuver = instruction.sign.toManeuverType(),
            instructionText = instruction.name.ifBlank { "Continue" },
            roadName = instruction.name.takeIf(String::isNotBlank),
            exitNumber = null,
            distanceMeters = instruction.distance.toInt(),
        )

    /**
     * The full sign vocabulary, confirmed via `javap` against the real 7.0 jar (not guessed) -
     * critically, GraphHopper has no distinct "off-ramp"/"exit"/"fork" sign at all. Motorway
     * exits surface only as `KEEP_LEFT`/`KEEP_RIGHT` (a fork/branch decision) or an ordinary
     * turn, indistinguishable at this level from a regular street turn - unlike Google's
     * `Maneuver.OFF_RAMP_RIGHT` etc. `KEEP_LEFT`/`KEEP_RIGHT` map to [ManeuverType.FORK_LEFT]/
     * [ManeuverType.FORK_RIGHT] as the closest real signal; see `SignboardGuidanceEngine`'s
     * GraphHopper-facing `build` overload for how exit/junction detection actually works instead
     * (OSM `motorway_junction` matching via `RouteRoadFeatureGuidance`, not the routing engine).
     */
    private fun Int.toManeuverType(): ManeuverType =
        when (this) {
            Instruction.CONTINUE_ON_STREET -> ManeuverType.STRAIGHT
            Instruction.TURN_LEFT -> ManeuverType.TURN_LEFT
            Instruction.TURN_RIGHT -> ManeuverType.TURN_RIGHT
            Instruction.TURN_SLIGHT_LEFT -> ManeuverType.TURN_SLIGHT_LEFT
            Instruction.TURN_SLIGHT_RIGHT -> ManeuverType.TURN_SLIGHT_RIGHT
            Instruction.TURN_SHARP_LEFT -> ManeuverType.TURN_SHARP_LEFT
            Instruction.TURN_SHARP_RIGHT -> ManeuverType.TURN_SHARP_RIGHT
            Instruction.KEEP_LEFT -> ManeuverType.FORK_LEFT
            Instruction.KEEP_RIGHT -> ManeuverType.FORK_RIGHT
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> ManeuverType.U_TURN
            Instruction.USE_ROUNDABOUT, Instruction.LEAVE_ROUNDABOUT -> ManeuverType.ROUNDABOUT
            Instruction.FINISH, Instruction.REACHED_VIA -> ManeuverType.DESTINATION
            else -> ManeuverType.UNKNOWN
        }

    private companion object {
        const val PROFILE_NAME = "car"
    }
}
