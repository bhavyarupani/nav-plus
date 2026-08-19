package com.navplus.core.routing.osrm

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
import javax.inject.Singleton

@Singleton
class OsrmRoutingEngine @Inject constructor(
    private val client: OkHttpClient,
) : RoutingEngine {

    override fun coversLocation(lat: Double, lng: Double) = true // online, always covers

    override suspend fun calculateRoutes(request: RoutingRequest): RoutingResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(request)
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) return@withContext RoutingResult.Error(Exception("HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext RoutingResult.Error(Exception("Empty body"))
                val routes = parseOsrmResponse(body, request)
                RoutingResult.Success(routes)
            } catch (e: Exception) {
                RoutingResult.Error(e)
            }
        }

    override fun close() = Unit

    private fun buildUrl(req: RoutingRequest): String {
        val coords = buildString {
            append("${req.origin.lng},${req.origin.lat}")
            req.waypoints.forEach { append(";${it.lng},${it.lat}") }
            append(";${req.destination.lng},${req.destination.lat}")
        }
        return "$BASE_URL/$coords?overview=full&geometries=geojson&steps=true&alternatives=${req.alternatives > 1}"
    }

    private fun parseOsrmResponse(json: String, req: RoutingRequest): List<Route> {
        val root = JSONObject(json)
        val routesArr = root.getJSONArray("routes")
        return (0 until routesArr.length()).map { i ->
            val r = routesArr.getJSONObject(i)
            val geometry = parseGeojsonCoords(r.getJSONObject("geometry"))
            val legs = r.getJSONArray("legs")
            val steps = mutableListOf<RouteStep>()
            for (l in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(s)
                    val maneuver = step.getJSONObject("maneuver")
                    val stepGeom = parseGeojsonCoords(step.getJSONObject("geometry"))
                    val start = stepGeom.firstOrNull() ?: continue
                    val end = stepGeom.lastOrNull() ?: start
                    steps.add(RouteStep(
                        instruction = step.optString("name", ""),
                        maneuver = maneuver.toManeuver(),
                        distanceMeters = step.getDouble("distance"),
                        durationSeconds = step.getDouble("duration").toLong(),
                        startLocation = start,
                        endLocation = end,
                        geometry = stepGeom,
                        streetName = step.optString("name").takeIf { it.isNotBlank() },
                    ))
                }
            }
            Route(
                id = "osrm_${UUID.randomUUID()}",
                waypoints = listOf(req.origin, req.destination),
                geometry = geometry,
                steps = steps,
                distanceMeters = r.getDouble("distance"),
                durationSeconds = r.getDouble("duration").toLong(),
            )
        }
    }

    private fun parseGeojsonCoords(geom: JSONObject): List<LatLng> {
        val coords = geom.getJSONArray("coordinates")
        return (0 until coords.length()).map { i ->
            val pt = coords.getJSONArray(i)
            LatLng(lat = pt.getDouble(1), lng = pt.getDouble(0))
        }
    }

    private fun JSONObject.toManeuver(): Maneuver {
        val type = optString("type", "")
        val modifier = optString("modifier", "")
        return when (type) {
            "depart"                      -> Maneuver.DEPART
            "arrive"                      -> Maneuver.ARRIVE
            "roundabout", "rotary"        -> Maneuver.ROUNDABOUT_ENTER
            "exit roundabout","exit rotary" -> Maneuver.ROUNDABOUT_EXIT
            "fork" -> if ("left" in modifier) Maneuver.FORK_LEFT else Maneuver.FORK_RIGHT
            "on ramp" -> Maneuver.ON_RAMP
            "off ramp" -> Maneuver.OFF_RAMP
            "merge" -> if ("left" in modifier) Maneuver.MERGE_LEFT else Maneuver.MERGE_RIGHT
            "turn", "end of road", "new name" -> when {
                modifier == "uturn"        -> Maneuver.U_TURN
                modifier == "sharp left"   -> Maneuver.TURN_SHARP_LEFT
                modifier == "sharp right"  -> Maneuver.TURN_SHARP_RIGHT
                modifier == "slight left"  -> Maneuver.TURN_SLIGHT_LEFT
                modifier == "slight right" -> Maneuver.TURN_SLIGHT_RIGHT
                modifier == "left"         -> Maneuver.TURN_LEFT
                modifier == "right"        -> Maneuver.TURN_RIGHT
                else                       -> Maneuver.STRAIGHT
            }
            else -> Maneuver.STRAIGHT
        }
    }

    companion object {
        private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    }
}
