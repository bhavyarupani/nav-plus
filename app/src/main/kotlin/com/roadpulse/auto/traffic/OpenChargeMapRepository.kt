package com.roadpulse.auto.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadpulse.auto.util.manifestMetadataString
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Free global EV-charger registry (openchargemap.org). Complements the Autobahn GmbH
 * facilities feed, which only covers charging directly on the Autobahn network — this fills
 * in chargers at rest stops, hotels, and towns off the motorway. Requires a free API key
 * (see README); returns empty rather than failing when unconfigured.
 */
class OpenChargeMapRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun chargersInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
    ): List<RoadFacility> {
        val context = appContext ?: return emptyList()
        val apiKey = manifestMetadataString(context, "com.roadpulse.auto.OPEN_CHARGE_MAP_API_KEY")
        if (apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") return emptyList()

        val bounds =
            ExpandedBounds(
                south = floor(southLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                west = floor(westLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                north = ceil(northLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                east = ceil(eastLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
            )
        val cacheFile = File(cacheDirectory, "${sha256(bounds.toString())}.json")
        val fresh =
            cacheFile.isFile &&
                System.currentTimeMillis() - cacheFile.lastModified() <= CACHE_MAX_AGE_MILLIS
        val json =
            when {
                fresh -> cacheFile.readText(Charsets.UTF_8)
                !hasValidatedInternet() && cacheFile.isFile -> cacheFile.readText(Charsets.UTF_8)
                !hasValidatedInternet() -> return emptyList()
                else ->
                    runCatching { download(bounds, apiKey) }.fold(
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

    internal fun parse(json: String): List<RoadFacility> {
        val elements = JSONArray(json)
        val results = mutableListOf<RoadFacility>()
        for (index in 0 until elements.length()) {
            val poi = elements.optJSONObject(index) ?: continue
            val addressInfo = poi.optJSONObject("AddressInfo") ?: continue
            val id = poi.optLong("ID", -1L).takeIf { it > 0 } ?: continue
            val latitude = addressInfo.optDouble("Latitude")
            val longitude = addressInfo.optDouble("Longitude")
            if (latitude.isNaN() || longitude.isNaN()) continue
            val title =
                addressInfo.optString("Title").takeIf(String::isNotBlank)
                    ?: poi.optJSONObject("OperatorInfo")?.optString("Title")?.takeIf(String::isNotBlank)
                    ?: "Charging station"
            val connections = poi.optJSONArray("Connections")
            val maxPower =
                (0 until (connections?.length() ?: 0))
                    .mapNotNull { connectionIndex ->
                        connections
                            ?.optJSONObject(connectionIndex)
                            ?.optDouble("PowerKW")
                            ?.takeIf { kw -> !kw.isNaN() }
                    }.maxOrNull()
                    ?.toInt()
            val operational = poi.optJSONObject("StatusType")?.optBoolean("IsOperational")
            results +=
                RoadFacility(
                    id = "openchargemap/$id",
                    roadId = "Open Charge Map",
                    type = RoadFacilityType.CHARGING,
                    coordinate = RoadCoordinate(latitude, longitude),
                    title = title,
                    subtitle = addressInfo.optString("AddressLine1"),
                    detail =
                        buildString {
                            maxPower?.let { append("Up to $it kW") }
                            if (operational == false) {
                                if (isNotEmpty()) append(" · ")
                                append("Reported non-operational")
                            }
                            if (isEmpty()) append("Open Charge Map")
                        },
                    maximumChargingPowerKw = maxPower,
                )
        }
        return results.distinctBy(RoadFacility::id)
    }

    private fun download(
        bounds: ExpandedBounds,
        apiKey: String,
    ): String {
        val boundingBox =
            String.format(
                Locale.US,
                "(%.6f,%.6f),(%.6f,%.6f)",
                bounds.south,
                bounds.west,
                bounds.north,
                bounds.east,
            )
        val url =
            "https://api.openchargemap.io/v3/poi/?output=json" +
                "&boundingbox=${URLEncoder.encode(boundingBox, Charsets.UTF_8.name())}" +
                "&maxresults=$MAX_RESULTS&compact=true&verbose=false" +
                "&key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "RoadPulse/0.1 personal Android navigation prototype")
            if (connection.responseCode !in 200..299) {
                error("Open Charge Map query returned HTTP ${connection.responseCode}")
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

    private data class ExpandedBounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

    companion object {
        private const val CACHE_DIRECTORY = "road-data/open-charge-map"
        private const val CACHE_GRID_DEGREES = 0.1
        private const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
        private const val MAX_RESULTS = 100
    }
}
