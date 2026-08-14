package com.roadpulse.auto.terrain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadpulse.auto.traffic.RoadCoordinate
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.round

class OpenMeteoElevationRepository private constructor(
    private val appContext: Context?,
    private val cacheFile: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_FILE_PATH),
    )

    fun profileForRoute(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
    ): ElevationProfileSummary =
        synchronized(CACHE_LOCK) {
            val requested = ElevationRouteAnalyzer.sampleAhead(route, current)
            require(requested.size >= 2) { "The active route is too short for an elevation profile" }
            val cells = requested.map(::terrainCell)
            val cache = readCache()
            val now = System.currentTimeMillis()
            val staleOrMissing =
                cells.distinctBy(::cellKey).filter { coordinate ->
                    val entry = cache[cellKey(coordinate)]
                    entry == null || now - entry.timestampMillis > CACHE_MAX_AGE_MILLIS
                }

            var downloaded = false
            if (staleOrMissing.isNotEmpty() && hasValidatedInternet()) {
                runCatching { download(staleOrMissing) }.onSuccess { elevations ->
                    staleOrMissing.zip(elevations).forEach { (coordinate, elevation) ->
                        cache[cellKey(coordinate)] = CachedElevation(elevation, now)
                    }
                    writeCache(cache)
                    downloaded = true
                }
            }

            val samples =
                cells.mapNotNull { coordinate ->
                    cache[cellKey(coordinate)]?.let { entry ->
                        ElevationSample(coordinate, entry.elevationMeters)
                    }
                }
            require(samples.size >= 2) { "No saved elevation profile is available while offline" }
            val oldestTimestamp = cells.mapNotNull { cache[cellKey(it)]?.timestampMillis }.minOrNull() ?: now
            ElevationRouteAnalyzer.summarize(
                samples = samples,
                timestampMillis = oldestTimestamp,
                usedSavedData = !downloaded,
            ) ?: error("The active route is too short for a reliable slope estimate")
        }

    private fun download(coordinates: List<RoadCoordinate>): List<Double> {
        require(coordinates.size <= MAX_COORDINATES_PER_REQUEST)
        val latitudes = coordinates.joinToString(",") { formatCoordinate(it.latitude) }
        val longitudes = coordinates.joinToString(",") { formatCoordinate(it.longitude) }
        val connection =
            URL(
                "$ENDPOINT?latitude=$latitudes&longitude=$longitudes",
            ).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) {
                error("Open-Meteo elevation returned HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val values = JSONObject(json).getJSONArray("elevation")
            require(values.length() == coordinates.size) { "Incomplete Open-Meteo elevation response" }
            List(values.length()) { index -> values.getDouble(index) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(): MutableMap<String, CachedElevation> {
        if (!cacheFile.isFile) return mutableMapOf()
        return runCatching {
            val entries =
                JSONObject(cacheFile.readText(Charsets.UTF_8)).optJSONObject("entries")
                    ?: JSONObject()
            buildMap {
                entries.keys().forEach { key ->
                    val value = entries.optJSONObject(key) ?: return@forEach
                    put(
                        key,
                        CachedElevation(
                            elevationMeters = value.getDouble("elevationMeters"),
                            timestampMillis = value.getLong("timestampMillis"),
                        ),
                    )
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeCache(cache: Map<String, CachedElevation>) {
        cacheFile.parentFile?.mkdirs()
        val entries = JSONObject()
        cache.forEach { (key, value) ->
            entries.put(
                key,
                JSONObject()
                    .put("elevationMeters", value.elevationMeters)
                    .put("timestampMillis", value.timestampMillis),
            )
        }
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        temporary.writeText(JSONObject().put("version", 1).put("entries", entries).toString(), Charsets.UTF_8)
        if (!temporary.renameTo(cacheFile)) {
            cacheFile.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun terrainCell(coordinate: RoadCoordinate): RoadCoordinate =
        RoadCoordinate(
            latitude = round(coordinate.latitude * CELL_ROUNDING_FACTOR) / CELL_ROUNDING_FACTOR,
            longitude = round(coordinate.longitude * CELL_ROUNDING_FACTOR) / CELL_ROUNDING_FACTOR,
        )

    private fun cellKey(coordinate: RoadCoordinate): String =
        String.format(
            Locale.US,
            "%.3f,%.3f",
            coordinate.latitude,
            coordinate.longitude,
        )

    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class CachedElevation(
        val elevationMeters: Double,
        val timestampMillis: Long,
    )

    companion object {
        const val ATTRIBUTION = "Terrain elevation: Open-Meteo, Copernicus DEM GLO-90"
        private const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        private const val CACHE_FILE_PATH = "road-data/elevation/open-meteo-glo90.json"
        private const val CACHE_MAX_AGE_MILLIS = 180L * 24 * 60 * 60 * 1_000
        private const val MAX_COORDINATES_PER_REQUEST = 100
        private const val CELL_ROUNDING_FACTOR = 1_000.0
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 12_000
        private const val USER_AGENT = "RoadPulse/0.1 personal Android navigation prototype"
        private val CACHE_LOCK = Any()
    }
}
