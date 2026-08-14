package com.roadpulse.auto.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.Locale

class AutobahnTrafficRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun eventsForRoads(roadIds: Set<String>): TrafficEventResult {
        val normalised =
            roadIds
                .asSequence()
                .map { it.replace(" ", "").uppercase(Locale.ROOT) }
                .filter { AUTOBAHN_REF.matches(it) }
                .distinct()
                .take(MAX_ROADS_PER_REFRESH)
                .toList()
        var usedSavedData = false
        var unavailableRoadCount = 0
        var newestTimestamp = 0L
        val events = mutableListOf<TrafficEvent>()
        normalised.forEach { roadId ->
            var roadAvailable = false
            SERVICES.forEach { service ->
                val cacheFile = File(cacheDirectory, "${roadId.lowercase()}-${service.path}.json")
                val loaded = runCatching { loadService(roadId, service, cacheFile) }
                loaded.onSuccess { cached ->
                    roadAvailable = true
                    usedSavedData = usedSavedData || cached.usedSavedData
                    newestTimestamp = maxOf(newestTimestamp, cached.timestampMillis)
                    events += parse(cached.json, roadId, service)
                }
            }
            if (!roadAvailable) unavailableRoadCount++
        }
        return TrafficEventResult(
            events = events.distinctBy(TrafficEvent::id),
            timestampMillis = newestTimestamp,
            usedSavedData = usedSavedData,
            unavailableRoadCount = unavailableRoadCount,
        )
    }

    internal fun parse(
        json: String,
        roadId: String,
        service: Service,
    ): List<TrafficEvent> {
        val root = JSONObject(json)
        val array = root.optJSONArray(service.responseKey) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("future", false)) continue
                val geometry = parseGeometry(item)
                if (geometry.isEmpty()) continue
                val abnormalType = item.optString("abnormalTrafficType")
                val type =
                    when {
                        service == Service.CLOSURE || item.optString("isBlocked") == "true" ->
                            TrafficEventType.CLOSURE
                        service == Service.ROADWORK -> TrafficEventType.ROADWORK
                        abnormalType.isNotBlank() -> TrafficEventType.QUEUE
                        else -> TrafficEventType.WARNING
                    }
                val description =
                    item
                        .optJSONArray("description")
                        ?.nonBlankStrings()
                        ?.filterNot { it.startsWith("Beginn:") || it == "Ereignismeldung:" }
                        ?.joinToString(" · ")
                        .orEmpty()
                val delay = item.optString("delayTimeValue").toIntOrNull()
                add(
                    TrafficEvent(
                        id =
                            item.optString("identifier").ifBlank {
                                "$roadId-${service.path}-${geometry.first()}"
                            },
                        roadId = roadId,
                        type = type,
                        title =
                            item.optString("title").ifBlank {
                                when (type) {
                                    TrafficEventType.CLOSURE -> "$roadId closure"
                                    TrafficEventType.ROADWORK -> "$roadId roadworks"
                                    TrafficEventType.QUEUE -> "$roadId traffic queue"
                                    TrafficEventType.WARNING -> "$roadId warning"
                                }
                            },
                        direction = item.optString("subtitle").trim(),
                        detail = description.ifBlank { type.displayName },
                        delayMinutes = delay,
                        startsAtMillis = parseTimestamp(item.optString("startTimestamp")),
                        geometry = geometry,
                        source = item.optString("source").ifBlank { "Autobahn GmbH" },
                    ),
                )
            }
        }
    }

    private fun loadService(
        roadId: String,
        service: Service,
        cacheFile: File,
    ): CachedResponse {
        val now = System.currentTimeMillis()
        val fresh = cacheFile.isFile && now - cacheFile.lastModified() <= LIVE_CACHE_AGE_MILLIS
        if (fresh) return CachedResponse(cacheFile.readText(), cacheFile.lastModified(), true)
        if (!hasValidatedInternet()) {
            check(cacheFile.isFile) { "No saved Autobahn traffic for $roadId" }
            return CachedResponse(cacheFile.readText(), cacheFile.lastModified(), true)
        }
        return runCatching {
            val json = download(roadId, service)
            JSONObject(json)
            saveAtomically(cacheFile, json)
            CachedResponse(json, cacheFile.lastModified(), false)
        }.getOrElse { error ->
            if (!cacheFile.isFile) throw error
            CachedResponse(cacheFile.readText(), cacheFile.lastModified(), true)
        }
    }

    private fun download(
        roadId: String,
        service: Service,
    ): String {
        val encodedRoad = URLEncoder.encode(roadId, Charsets.UTF_8.name())
        val connection =
            URL("$BASE_URL/$encodedRoad/services/${service.path}")
                .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty(
                "User-Agent",
                "RoadPulse/0.1 personal Android navigation prototype",
            )
            if (connection.responseCode !in 200..299) {
                error("Autobahn API returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeometry(item: JSONObject): List<RoadCoordinate> {
        val coordinates = item.optJSONObject("geometry")?.optJSONArray("coordinates")
        if (coordinates != null) {
            val parsed =
                buildList {
                    for (index in 0 until coordinates.length()) {
                        val pair = coordinates.optJSONArray(index) ?: continue
                        if (pair.length() < 2) continue
                        add(RoadCoordinate(pair.optDouble(1), pair.optDouble(0)))
                    }
                }
            if (parsed.isNotEmpty()) return parsed
        }
        val coordinate = item.optJSONObject("coordinate") ?: return emptyList()
        if (!coordinate.has("lat") || !coordinate.has("long")) return emptyList()
        return listOf(RoadCoordinate(coordinate.optDouble("lat"), coordinate.optDouble("long")))
    }

    private fun JSONArray.nonBlankStrings(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }

    private fun parseTimestamp(value: String): Long? =
        runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull()

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

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    enum class Service(
        val path: String,
        val responseKey: String,
    ) {
        WARNING("warning", "warning"),
        ROADWORK("roadworks", "roadworks"),
        CLOSURE("closure", "closure"),
    }

    private data class CachedResponse(
        val json: String,
        val timestampMillis: Long,
        val usedSavedData: Boolean,
    )

    companion object {
        const val ATTRIBUTION = "Live motorway events: Autobahn GmbH des Bundes"
        private const val BASE_URL = "https://verkehr.autobahn.de/o/autobahn"
        private const val CACHE_DIRECTORY = "road-data/autobahn-events"
        private const val LIVE_CACHE_AGE_MILLIS = 3 * 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 12_000
        private const val MAX_ROADS_PER_REFRESH = 8
        private val AUTOBAHN_REF = Regex("A[0-9]{1,3}[A-Z]?")
        private val SERVICES = Service.entries
    }
}

val TrafficEventType.displayName: String
    get() =
        when (this) {
            TrafficEventType.QUEUE -> "Traffic queue"
            TrafficEventType.WARNING -> "Road warning"
            TrafficEventType.ROADWORK -> "Roadworks"
            TrafficEventType.CLOSURE -> "Road closure"
        }
