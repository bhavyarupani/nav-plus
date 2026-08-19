package com.navplus.core.routing.tomtom

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.RoutingRequest
import com.navplus.core.routing.RoutingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TomTomRoutingEngine @Inject constructor(
    private val client: OkHttpClient,
    @Named("tomtom_api_key") private val apiKey: String,
) : RoutingEngine {

    @Volatile private var quotaResetAt = 0L

    override fun coversLocation(lat: Double, lng: Double): Boolean =
        apiKey.isNotEmpty() && System.currentTimeMillis() >= quotaResetAt

    override suspend fun calculateRoutes(request: RoutingRequest): RoutingResult =
        withContext(Dispatchers.IO) {
            try {
                val waypoints = buildWaypoints(request)
                val url = "https://api.tomtom.com/routing/1/calculateRoute/$waypoints/json" +
                    "?key=$apiKey&traffic=true&travelMode=car&routeType=fastest" +
                    "&maxAlternatives=${(request.alternatives - 1).coerceAtLeast(0)}" +
                    (if (request.avoidTolls) "&avoid=tollRoads" else "") +
                    (if (request.avoidHighways) "&avoid=motorways" else "") +
                    (if (request.avoidFerries) "&avoid=ferries" else "")

                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.code == 429) {
                    quotaResetAt = System.currentTimeMillis() + 60 * 60 * 1000L
                    return@withContext RoutingResult.Error(Exception("TomTom quota exceeded"))
                }
                if (!response.isSuccessful) {
                    return@withContext RoutingResult.Error(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext RoutingResult.Error(Exception("Empty body"))
                RoutingResult.Success(parseTomTomRoutes(body, request))
            } catch (e: Exception) {
                RoutingResult.Error(e)
            }
        }

    override fun close() = Unit

    private fun buildWaypoints(req: RoutingRequest): String = buildString {
        append("${req.origin.lat},${req.origin.lng}")
        req.waypoints.forEach { append(":${it.lat},${it.lng}") }
        append(":${req.destination.lat},${req.destination.lng}")
    }

    private fun parseTomTomRoutes(json: String, req: RoutingRequest): List<Route> {
        val root = JSONObject(json)
        val routes = root.getJSONArray("routes")
        return (0 until routes.length()).mapNotNull { i ->
            try {
                val r = routes.getJSONObject(i)
                val summary = r.getJSONObject("summary")
                val legs = r.getJSONArray("legs")

                val geometry = mutableListOf<LatLng>()
                val steps = mutableListOf<RouteStep>()

                for (l in 0 until legs.length()) {
                    val leg = legs.getJSONObject(l)
                    val points = leg.getJSONArray("points")
                    for (p in 0 until points.length()) {
                        val pt = points.getJSONObject(p)
                        geometry.add(LatLng(pt.getDouble("latitude"), pt.getDouble("longitude")))
                    }
                }

                val instructions = r.optJSONObject("guidance")?.optJSONArray("instructions")
                if (instructions != null) {
                    val totalLen = summary.getDouble("lengthInMeters").coerceAtLeast(1.0)
                    for (s in 0 until instructions.length()) {
                        val instr = instructions.getJSONObject(s)
                        val offsetMeters = instr.optDouble("routeOffsetInMeters", 0.0)
                        val idx = (offsetMeters / (totalLen / geometry.size.coerceAtLeast(1))).toInt()
                        val point = if (idx < geometry.size) geometry[idx] else geometry.lastOrNull() ?: continue
                        steps.add(RouteStep(
                            instruction = instr.optString("message", ""),
                            maneuver = instr.optString("maneuver", "").toManeuver(),
                            distanceMeters = offsetMeters,
                            durationSeconds = instr.optLong("travelTimeInSeconds", 0L),
                            startLocation = point,
                            endLocation = point,
                            geometry = listOf(point),
                            streetName = instr.optString("street").takeIf { it.isNotBlank() },
                        ))
                    }
                }

                Route(
                    id = "tt_${UUID.randomUUID()}",
                    waypoints = listOf(req.origin, req.destination),
                    geometry = geometry,
                    steps = steps,
                    distanceMeters = summary.getDouble("lengthInMeters"),
                    durationSeconds = summary.getLong("travelTimeInSeconds"),
                )
            } catch (e: Exception) { null }
        }
    }

    private fun String.toManeuver(): Maneuver = when (this) {
        "DEPART"                -> Maneuver.DEPART
        "ARRIVE"                -> Maneuver.ARRIVE
        "TURN_LEFT"             -> Maneuver.TURN_LEFT
        "TURN_RIGHT"            -> Maneuver.TURN_RIGHT
        "KEEP_LEFT", "BEAR_LEFT"  -> Maneuver.TURN_SLIGHT_LEFT
        "KEEP_RIGHT", "BEAR_RIGHT" -> Maneuver.TURN_SLIGHT_RIGHT
        "SHARP_LEFT"            -> Maneuver.TURN_SHARP_LEFT
        "SHARP_RIGHT"           -> Maneuver.TURN_SHARP_RIGHT
        "MAKE_UTURN"            -> Maneuver.U_TURN
        "ENTER_ROUNDABOUT"      -> Maneuver.ROUNDABOUT_ENTER
        "EXIT_ROUNDABOUT"       -> Maneuver.ROUNDABOUT_EXIT
        "MOTORWAY_ENTER", "MOTORWAY_CHANGE" -> Maneuver.ON_RAMP
        "MOTORWAY_EXIT"         -> Maneuver.OFF_RAMP
        else                    -> Maneuver.STRAIGHT
    }
}
