package com.roadpulse.auto.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadpulse.auto.util.manifestMetadataString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Live traffic incidents (congestion, closures, roadworks) from TomTom's Traffic API
 * (api.tomtom.com/traffic/services/5/incidentDetails) - free, self-service, no billing account
 * (unlike Google Maps Platform, which requires one regardless of usage - see
 * ZERO_COST_ARCHITECTURE.md for why this project avoids that entirely). Complements
 * [AutobahnTrafficRepository], which only ever covers named German Autobahn refs: this fills in
 * every other country the region-download catalog now covers (Austria, Switzerland, Italy,
 * Slovakia, Slovenia, Croatia, ...), one bbox-based API instead of a per-country DATEX II
 * integration - DATEX II was evaluated and rejected here specifically because most national
 * feeds (confirmed for Switzerland's ASTRA/opentransportdata.swiss) reference locations via a
 * separately-licensed AlertC/TMC location-code table rather than embedding coordinates, which
 * TomTom's `geometry.coordinates` does directly.
 *
 * Requires a free API key (see README) configured the same way as
 * [OpenChargeMapRepository]/`TankerkoenigRepository` - returns empty rather than failing when
 * unconfigured, so this is a no-op until a real key is added to `local.properties`.
 */
class TomTomTrafficRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun eventsInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
    ): TrafficEventResult {
        val context = appContext ?: return EMPTY_RESULT
        val apiKey = manifestMetadataString(context, "com.roadpulse.auto.TOMTOM_API_KEY")
        if (apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") return EMPTY_RESULT

        val bounds =
            ExpandedBounds(
                south = floor(southLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                west = floor(westLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                north = ceil(northLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                east = ceil(eastLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
            )
        val cacheFile = File(cacheDirectory, "${sha256(bounds.toString())}.json")
        val now = System.currentTimeMillis()
        val fresh = cacheFile.isFile && now - cacheFile.lastModified() <= LIVE_CACHE_AGE_MILLIS
        var usedSavedData = false
        val json =
            when {
                fresh -> cacheFile.readText(Charsets.UTF_8)
                !hasValidatedInternet() && cacheFile.isFile -> {
                    usedSavedData = true
                    cacheFile.readText(Charsets.UTF_8)
                }
                !hasValidatedInternet() -> return EMPTY_RESULT
                else ->
                    runCatching { download(bounds, apiKey) }.fold(
                        onSuccess = { downloaded ->
                            saveAtomically(cacheFile, downloaded)
                            downloaded
                        },
                        onFailure = {
                            if (!cacheFile.isFile) return EMPTY_RESULT
                            usedSavedData = true
                            cacheFile.readText(Charsets.UTF_8)
                        },
                    )
            }
        return TrafficEventResult(
            events = parse(json),
            timestampMillis = cacheFile.lastModified(),
            usedSavedData = usedSavedData,
            unavailableRoadCount = 0,
        )
    }

    internal fun parse(json: String): List<TrafficEvent> {
        val incidents = runCatching { JSONObject(json).optJSONArray("incidents") }.getOrNull() ?: JSONArray()
        return buildList {
            for (index in 0 until incidents.length()) {
                val incident = incidents.optJSONObject(index) ?: continue
                val properties = incident.optJSONObject("properties") ?: continue
                val geometry = parseGeometry(incident.optJSONObject("geometry")) ?: continue
                val type = classify(properties)
                val id = incident.optString("id").ifBlank { "tomtom-${geometry.first()}-${type.name}" }
                add(
                    TrafficEvent(
                        id = id,
                        roadId =
                            properties
                                .optJSONArray("roadNumbers")
                                ?.optString(0)
                                .orEmpty()
                                .ifBlank { "TomTom" },
                        type = type,
                        title = eventDescription(properties) ?: type.displayName,
                        direction = "",
                        detail = eventDescription(properties) ?: type.displayName,
                        delayMinutes =
                            properties.optDouble("delay").let { seconds ->
                                if (seconds.isNaN()) null else (seconds / 60).toInt()
                            },
                        startsAtMillis = parseTimestamp(properties.optString("startTime")),
                        geometry = geometry,
                        source = "TomTom",
                    ),
                )
            }
        }.distinctBy(TrafficEvent::id)
    }

    /** The first non-blank description found among an incident's `events` sub-objects - TomTom
     * groups several human-readable event descriptions (cause, delay magnitude, etc.) there. */
    private fun eventDescription(properties: JSONObject): String? {
        val events = properties.optJSONArray("events") ?: return null
        for (index in 0 until events.length()) {
            val description = events.optJSONObject(index)?.optString("description")?.trim()
            if (!description.isNullOrBlank()) return description
        }
        return null
    }

    /** `iconCategory` has been observed as both a numeric code and a string enum name across
     * TomTom's API generations - handled defensively rather than assuming one. */
    private fun classify(properties: JSONObject): TrafficEventType {
        val raw = properties.opt("iconCategory")
        val code = (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull()
        val name = (raw as? String)?.uppercase(Locale.ROOT)
        return when {
            code == 9 || name == "ROAD_WORKS" || name == "ROADWORKS" -> TrafficEventType.ROADWORK
            code == 8 || code == 7 || name == "ROAD_CLOSED" || name == "LANE_CLOSED" -> TrafficEventType.CLOSURE
            code == 6 || name == "JAM" || name == "CLUSTER" -> TrafficEventType.QUEUE
            else -> TrafficEventType.WARNING
        }
    }

    private fun parseGeometry(geometry: JSONObject?): List<RoadCoordinate>? {
        val coordinates = geometry?.optJSONArray("coordinates") ?: return null
        val type = geometry.optString("type")
        return if (type.equals("Point", ignoreCase = true)) {
            if (coordinates.length() < 2) return null
            listOf(RoadCoordinate(coordinates.optDouble(1), coordinates.optDouble(0)))
        } else {
            buildList {
                for (index in 0 until coordinates.length()) {
                    val pair = coordinates.optJSONArray(index) ?: continue
                    if (pair.length() < 2) continue
                    add(RoadCoordinate(pair.optDouble(1), pair.optDouble(0)))
                }
            }.ifEmpty { null }
        }
    }

    private fun parseTimestamp(value: String): Long? = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    private fun download(
        bounds: ExpandedBounds,
        apiKey: String,
    ): String {
        val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", bounds.west, bounds.south, bounds.east, bounds.north)
        val fields =
            URLEncoder.encode(
                "{incidents{type,geometry{type,coordinates}," +
                    "properties{iconCategory,delay,roadNumbers,startTime,endTime,events{description}}}}",
                Charsets.UTF_8.name(),
            )
        val url =
            "$BASE_URL?key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}" +
                "&bbox=${URLEncoder.encode(bbox, Charsets.UTF_8.name())}" +
                "&fields=$fields&language=en-GB&timeValidityFilter=present"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "RoadPulse/0.1 personal Android navigation prototype")
            if (connection.responseCode !in 200..299) {
                error("TomTom traffic query returned HTTP ${connection.responseCode}")
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
        const val ATTRIBUTION = "Live traffic outside Germany: TomTom Traffic API"
        private val EMPTY_RESULT = TrafficEventResult(emptyList(), 0L, false, 0)
        private const val BASE_URL = "https://api.tomtom.com/traffic/services/5/incidentDetails"
        private const val CACHE_DIRECTORY = "road-data/tomtom-traffic"
        private const val CACHE_GRID_DEGREES = 0.2
        private const val LIVE_CACHE_AGE_MILLIS = 3 * 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 12_000
    }
}
