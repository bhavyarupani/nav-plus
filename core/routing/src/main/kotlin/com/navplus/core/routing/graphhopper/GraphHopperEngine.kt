package com.navplus.core.routing.graphhopper

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.RouteStyle
import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.RoutingRequest
import com.navplus.core.routing.RoutingResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphHopperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : RoutingEngine {

    private var hopper: GraphHopper? = null
    private val graphDir: File get() = File(context.filesDir, "routing/graph")

    fun load() {
        if (!graphDir.exists()) return
        hopper = GraphHopper().apply {
            graphHopperLocation = graphDir.absolutePath
            setProfiles(
                Profile("car")
                    .setCustomModel(com.graphhopper.util.GHUtility.loadCustomModelFromJar("car"))
                    .setWeighting("custom"),
                Profile("car_short")
                    .setCustomModel(
                        com.graphhopper.util.GHUtility.loadCustomModelFromJar("car")
                            .setDistanceInfluence(200.0 as Double?)
                    )
                    .setWeighting("custom"),
            )
            chPreparationHandler.setCHProfiles(
                CHProfile("car"),
                CHProfile("car_short"),
            )
            importOrLoad()
        }
    }

    override fun coversLocation(lat: Double, lng: Double): Boolean =
        hopper?.baseGraph != null

    override suspend fun calculateRoutes(request: RoutingRequest): RoutingResult =
        withContext(Dispatchers.Default) {
            val h = hopper ?: return@withContext RoutingResult.NoOfflineCoverage(listOf("No graph loaded"))
            try {
                val ghRequest = GHRequest(
                    request.origin.lat, request.origin.lng,
                    request.destination.lat, request.destination.lng,
                ).apply {
                    profile = if (request.style == RouteStyle.SCENIC) "car_short" else "car"
                    putHint(Parameters.Routing.CALC_POINTS, true)
                    putHint(Parameters.Routing.INSTRUCTIONS, true)
                    putHint("alternative_route.max_paths", request.alternatives)
                    if (request.alternatives > 1) algorithm = Parameters.Algorithms.ALT_ROUTE
                }

                val response: GHResponse = h.route(ghRequest)
                if (response.hasErrors()) {
                    return@withContext RoutingResult.Error(Exception(response.errors.first().message))
                }

                val routes = response.all.mapIndexed { index, path ->
                    val allPoints = path.points
                    Route(
                        id = "gh_route_$index",
                        waypoints = listOf(request.origin, request.destination),
                        geometry = allPoints.toNavPlusLatLngs(),
                        steps = path.instructions.mapIndexed { stepIdx, instr ->
                            val instrPoints = instr.getPoints().toNavPlusLatLngs()
                            RouteStep(
                                instruction = instr.name ?: "",
                                maneuver = instr.sign.toManeuver(),
                                distanceMeters = instr.distance,
                                durationSeconds = instr.time / 1000,
                                startLocation = instrPoints.firstOrNull() ?: LatLng(allPoints.getLat(0), allPoints.getLon(0)),
                                endLocation = instrPoints.lastOrNull() ?: LatLng(allPoints.getLat(allPoints.size() - 1), allPoints.getLon(allPoints.size() - 1)),
                                geometry = instrPoints,
                                streetName = instr.name,
                            )
                        },
                        distanceMeters = path.distance,
                        durationSeconds = path.time / 1000,
                        style = request.style,
                        hasTolls = false,
                        ascendMeters = path.ascend,
                        descendMeters = path.descend,
                    )
                }
                RoutingResult.Success(routes)
            } catch (e: Exception) {
                RoutingResult.Error(e)
            }
        }

    override fun close() {
        hopper?.close()
        hopper = null
    }
}

private fun com.graphhopper.util.PointList.toNavPlusLatLngs(): List<LatLng> =
    (0 until size()).map { i -> LatLng(getLat(i), getLon(i)) }

private fun Int.toManeuver(): Maneuver = when (this) {
    com.graphhopper.util.Instruction.TURN_LEFT         -> Maneuver.TURN_LEFT
    com.graphhopper.util.Instruction.TURN_RIGHT        -> Maneuver.TURN_RIGHT
    com.graphhopper.util.Instruction.TURN_SLIGHT_LEFT  -> Maneuver.TURN_SLIGHT_LEFT
    com.graphhopper.util.Instruction.TURN_SLIGHT_RIGHT -> Maneuver.TURN_SLIGHT_RIGHT
    com.graphhopper.util.Instruction.TURN_SHARP_LEFT   -> Maneuver.TURN_SHARP_LEFT
    com.graphhopper.util.Instruction.TURN_SHARP_RIGHT  -> Maneuver.TURN_SHARP_RIGHT
    com.graphhopper.util.Instruction.USE_ROUNDABOUT    -> Maneuver.ROUNDABOUT_ENTER
    com.graphhopper.util.Instruction.LEAVE_ROUNDABOUT  -> Maneuver.ROUNDABOUT_EXIT
    com.graphhopper.util.Instruction.FINISH            -> Maneuver.ARRIVE
    com.graphhopper.util.Instruction.REACHED_VIA       -> Maneuver.ARRIVE
    com.graphhopper.util.Instruction.U_TURN_LEFT,
    com.graphhopper.util.Instruction.U_TURN_RIGHT      -> Maneuver.U_TURN
    com.graphhopper.util.Instruction.KEEP_LEFT         -> Maneuver.KEEP_LEFT
    com.graphhopper.util.Instruction.KEEP_RIGHT        -> Maneuver.KEEP_RIGHT
    else                                               -> Maneuver.STRAIGHT
}
