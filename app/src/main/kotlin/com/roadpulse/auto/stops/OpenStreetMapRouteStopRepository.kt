package com.roadpulse.auto.stops

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.roadpulse.auto.traffic.RoadCoordinate
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

enum class OsmRouteStopCategory { SUPERMARKET, FUEL }

data class OsmRouteStopCandidate(
    val id: String,
    val coordinate: RoadCoordinate,
    val category: OsmRouteStopCategory,
    val name: String,
    val openingHours: String?,
)

/**
 * Free OpenStreetMap replacement for Google Places "search along route": finds
 * supermarkets and fuel stations within a buffer of the route polyline via the
 * public Overpass API, using the same endpoint-fallback and file-cache pattern
 * as [com.roadpulse.auto.alerts.OpenStreetMapCameraRepository].
 */
class OpenStreetMapRouteStopRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun stopsAlongRoute(
        routePoints: List<RoadCoordinate>,
        categories: Set<OsmRouteStopCategory>,
    ): List<OsmRouteStopCandidate> =
        synchronized(QUERY_LOCK) {
            if (categories.isEmpty() || routePoints.size < 2) return emptyList()
            val query = overpassQuery(routePoints.downsampledForQuery(MAX_QUERY_POINTS), categories)
            val cacheFile = File(cacheDirectory, "${sha256(query)}.json")
            val fresh =
                cacheFile.isFile &&
                    System.currentTimeMillis() - cacheFile.lastModified() <= CACHE_MAX_AGE_MILLIS
            val json =
                when {
                    fresh -> cacheFile.readText(Charsets.UTF_8)
                    !hasValidatedInternet() && cacheFile.isFile -> cacheFile.readText(Charsets.UTF_8)
                    !hasValidatedInternet() -> return emptyList()
                    else ->
                        runCatching { download(query) }.fold(
                            onSuccess = { downloaded ->
                                saveAtomically(cacheFile, downloaded)
                                downloaded
                            },
                            onFailure = { error ->
                                Log.w(TAG, "OpenStreetMap route stop query failed", error)
                                if (!cacheFile.isFile) return emptyList()
                                cacheFile.readText(Charsets.UTF_8)
                            },
                        )
                }
            parse(json)
        }

    internal fun parse(json: String): List<OsmRouteStopCandidate> {
        val elements = JSONObject(json).getJSONArray("elements")
        val results = mutableListOf<OsmRouteStopCandidate>()
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            val center = element.optJSONObject("center")
            val latitude =
                when {
                    element.has("lat") -> element.optDouble("lat")
                    center?.has("lat") == true -> center.optDouble("lat")
                    else -> continue
                }
            val longitude =
                when {
                    element.has("lon") -> element.optDouble("lon")
                    center?.has("lon") == true -> center.optDouble("lon")
                    else -> continue
                }
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val category =
                when {
                    tags.optString("shop") == "supermarket" -> OsmRouteStopCategory.SUPERMARKET
                    tags.optString("amenity") == "fuel" -> OsmRouteStopCategory.FUEL
                    else -> continue
                }
            val name =
                sequenceOf(
                    tags.optString("brand"),
                    tags.optString("name"),
                    tags.optString("operator"),
                ).firstOrNull(String::isNotBlank).orEmpty()
            results +=
                OsmRouteStopCandidate(
                    id = "${element.optString("type", "object")}/${element.optLong("id")}",
                    coordinate = RoadCoordinate(latitude, longitude),
                    category = category,
                    name = name,
                    openingHours = tags.optString("opening_hours").takeIf(String::isNotBlank),
                )
        }
        return results.distinctBy(OsmRouteStopCandidate::id)
    }

    private fun overpassQuery(
        points: List<RoadCoordinate>,
        categories: Set<OsmRouteStopCategory>,
    ): String {
        // A bare `around` corridor check has to distance-test every matching node in
        // the dataset against every route point, which times out on a long trip. A
        // bounding-box filter first (cheap, uses Overpass's spatial index) narrows the
        // candidates before the more expensive corridor check runs on what's left.
        val paddingDegrees = CORRIDOR_RADIUS_METERS / 111_000.0
        val south = points.minOf { it.latitude } - paddingDegrees
        val north = points.maxOf { it.latitude } + paddingDegrees
        val west = points.minOf { it.longitude } - paddingDegrees
        val east = points.maxOf { it.longitude } + paddingDegrees
        val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)
        val around =
            "around:${CORRIDOR_RADIUS_METERS.toInt()}," +
                points.joinToString(",") { point ->
                    String.format(Locale.US, "%.5f,%.5f", point.latitude, point.longitude)
                }
        val clauses =
            buildString {
                if (OsmRouteStopCategory.SUPERMARKET in categories) {
                    append("nwr[\"shop\"=\"supermarket\"]($bbox)($around);")
                }
                if (OsmRouteStopCategory.FUEL in categories) {
                    append("nwr[\"amenity\"=\"fuel\"]($bbox)($around);")
                }
            }
        return "[out:json][timeout:25];($clauses);out center tags;"
    }

    private fun List<RoadCoordinate>.downsampledForQuery(maxPoints: Int): List<RoadCoordinate> {
        if (size <= maxPoints) return this
        val step = (size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { index -> this[(index * step).toInt().coerceAtMost(lastIndex)] }
    }

    private fun download(query: String): String {
        val payload = "data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        var lastFailure: Exception? = null
        OVERPASS_ENDPOINTS.forEach { endpoint ->
            try {
                return downloadFrom(endpoint, payload)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw IllegalStateException("Every OpenStreetMap query endpoint was unavailable", lastFailure)
    }

    private fun downloadFrom(
        endpoint: String,
        payload: String,
    ): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty(
                "User-Agent",
                "RoadPulse/0.1 personal Android navigation prototype",
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                error("OpenStreetMap query returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun saveAtomically(
        file: File,
        value: String,
    ) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(value, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val TAG = "OSMRouteStopRepository"
        private val OVERPASS_ENDPOINTS =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
            )
        private const val CACHE_DIRECTORY = "road-data/openstreetmap-route-stops"
        private const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 30_000

        // Keep the corridor point count bounded so a long trip still produces a
        // reasonably sized query for the shared public Overpass instances.
        private const val MAX_QUERY_POINTS = 120
        internal const val CORRIDOR_RADIUS_METERS = 3_000.0
        private val QUERY_LOCK = Any()
    }
}
