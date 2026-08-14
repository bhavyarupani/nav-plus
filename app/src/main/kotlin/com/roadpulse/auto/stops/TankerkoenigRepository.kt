package com.roadpulse.auto.stops

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.util.manifestMetadataString
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class TankerkoenigStation(
    val id: String,
    val coordinate: RoadCoordinate,
    val name: String,
    val brand: String,
    val isOpenNow: Boolean,
    val dieselPrice: Double?,
    val e5Price: Double?,
    val e10Price: Double?,
)

/**
 * Free German real-time fuel-price feed (creativecommons.tankerkoenig.de). Germany-only, and
 * requires a free API key (see README) — returns empty rather than failing when unconfigured.
 * Complements the OSM-based fuel search: RouteStopOptimizer matches these by proximity to show
 * live diesel/E5/E10 prices on candidates it already found, rather than replacing that search.
 */
class TankerkoenigRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun stationsAlongRoute(routePoints: List<RoadCoordinate>): List<TankerkoenigStation> {
        val context = appContext ?: return emptyList()
        if (routePoints.size < 2) return emptyList()
        val apiKey = manifestMetadataString(context, "com.roadpulse.auto.TANKERKOENIG_API_KEY")
        if (apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") return emptyList()

        val stations = LinkedHashMap<String, TankerkoenigStation>()
        routePoints.sampledForQuery(MAX_QUERY_POINTS).forEach { point ->
            runCatching { stationsNear(point, apiKey) }
                .getOrDefault(emptyList())
                .forEach { stations.putIfAbsent(it.id, it) }
        }
        return stations.values.toList()
    }

    private fun stationsNear(
        point: RoadCoordinate,
        apiKey: String,
    ): List<TankerkoenigStation> {
        val cacheKey = String.format(Locale.US, "%.3f,%.3f", point.latitude, point.longitude)
        val cacheFile = File(cacheDirectory, "${sha256(cacheKey)}.json")
        val fresh =
            cacheFile.isFile &&
                System.currentTimeMillis() - cacheFile.lastModified() <= CACHE_MAX_AGE_MILLIS
        val json =
            when {
                fresh -> cacheFile.readText(Charsets.UTF_8)
                !hasValidatedInternet() && cacheFile.isFile -> cacheFile.readText(Charsets.UTF_8)
                !hasValidatedInternet() -> return emptyList()
                else ->
                    runCatching { download(point, apiKey) }.fold(
                        onSuccess = { downloaded ->
                            saveAtomically(cacheFile, downloaded)
                            downloaded
                        },
                        onFailure = {
                            if (!cacheFile.isFile) return emptyList()
                            cacheFile.readText(Charsets.UTF_8)
                        },
                    )
            }
        return parse(json)
    }

    internal fun parse(json: String): List<TankerkoenigStation> {
        val root = JSONObject(json)
        if (!root.optBoolean("ok", false)) return emptyList()
        val stations = root.optJSONArray("stations") ?: return emptyList()
        val results = mutableListOf<TankerkoenigStation>()
        for (index in 0 until stations.length()) {
            val station = stations.getJSONObject(index)
            val id = station.optString("id").takeIf(String::isNotBlank) ?: continue
            results +=
                TankerkoenigStation(
                    id = id,
                    coordinate = RoadCoordinate(station.optDouble("lat"), station.optDouble("lng")),
                    name = station.optString("name"),
                    brand = station.optString("brand"),
                    isOpenNow = station.optBoolean("isOpen", false),
                    dieselPrice = station.positivePriceOrNull("diesel"),
                    e5Price = station.positivePriceOrNull("e5"),
                    e10Price = station.positivePriceOrNull("e10"),
                )
        }
        return results
    }

    private fun JSONObject.positivePriceOrNull(key: String): Double? {
        if (isNull(key)) return null
        val value = optDouble(key, -1.0)
        return value.takeIf { it > 0.0 }
    }

    private fun download(
        point: RoadCoordinate,
        apiKey: String,
    ): String {
        val url =
            String.format(
                Locale.US,
                "https://creativecommons.tankerkoenig.de/json/list.php" +
                    "?lat=%.6f&lng=%.6f&rad=%d&sort=dist&type=all&apikey=%s",
                point.latitude,
                point.longitude,
                QUERY_RADIUS_KM,
                URLEncoder.encode(apiKey, Charsets.UTF_8.name()),
            )
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "RoadPulse/0.1 personal Android navigation prototype")
            if (connection.responseCode !in 200..299) {
                error("Tankerkoenig query returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun List<RoadCoordinate>.sampledForQuery(maxPoints: Int): List<RoadCoordinate> {
        if (size <= maxPoints) return this
        val step = (size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { index -> this[(index * step).toInt().coerceAtMost(lastIndex)] }
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
        private const val CACHE_DIRECTORY = "road-data/tankerkoenig"
        private const val CACHE_MAX_AGE_MILLIS = 20 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val QUERY_RADIUS_KM = 10
        private const val MAX_QUERY_POINTS = 6
    }
}
