package com.roadpulse.auto.alerts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class OpenStreetMapCameraRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val offlineDirectory =
        File(
            File(appContext.filesDir, OFFLINE_DATA_DIRECTORY),
            OPENSTREETMAP_DIRECTORY,
        ).apply { mkdirs() }

    init {
        migrateTemporaryCache(File(appContext.cacheDir, LEGACY_CACHE_DIRECTORY))
    }

    @Volatile
    var lastResultTimestampMillis: Long = 0L
        private set

    @Volatile
    var lastResultUsedSavedData: Boolean = false
        private set

    val storedRegionCount: Int
        get() = offlineDirectory.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0

    val storedBytes: Long
        get() = offlineDirectory.listFiles()?.sumOf(File::length) ?: 0L

    fun enforcementLocationsInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
        referenceLatitude: Double,
        referenceLongitude: Double,
    ): List<NearbyOpenGatsoPoi> =
        synchronized(QUERY_LOCK) {
            val queryBounds =
                ExpandedBounds(
                    south = floor(southLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    west = floor(westLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    north = ceil(northLatitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                    east = ceil(eastLongitude / CACHE_GRID_DEGREES) * CACHE_GRID_DEGREES,
                )
            val query = overpassQuery(queryBounds)
            val cacheFile = File(offlineDirectory, "${sha256(query)}.json")
            val freshSavedData =
                cacheFile.isFile &&
                    System.currentTimeMillis() - cacheFile.lastModified() <= CACHE_MAX_AGE_MILLIS
            val response =
                if (
                    freshSavedData
                ) {
                    lastResultUsedSavedData = true
                    cacheFile.readText(Charsets.UTF_8)
                } else if (!hasValidatedInternet()) {
                    check(cacheFile.isFile) { "No saved OpenStreetMap camera data for this area" }
                    lastResultUsedSavedData = true
                    cacheFile.readText(Charsets.UTF_8)
                } else {
                    runCatching { download(query) }
                        .onSuccess { downloaded ->
                            lastResultUsedSavedData = false
                            val temporaryFile = File(offlineDirectory, "${cacheFile.name}.tmp")
                            temporaryFile.writeText(downloaded, Charsets.UTF_8)
                            if (!temporaryFile.renameTo(cacheFile)) {
                                cacheFile.writeText(downloaded, Charsets.UTF_8)
                                temporaryFile.delete()
                            }
                        }.getOrElse { error ->
                            if (cacheFile.isFile) {
                                lastResultUsedSavedData = true
                                cacheFile.readText(Charsets.UTF_8)
                            } else {
                                throw error
                            }
                        }
                }
            lastResultTimestampMillis = osmDataTimestamp(response).takeIf { it > 0L }
                ?: cacheFile.lastModified()
            parse(response, referenceLatitude, referenceLongitude, lastResultTimestampMillis)
                .filter { item ->
                    item.poi.latitude in southLatitude..northLatitude &&
                        longitudeIsInside(item.poi.longitude, westLongitude, eastLongitude)
                }
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
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("OpenStreetMap query returned HTTP $statusCode")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun migrateTemporaryCache(legacyDirectory: File) {
        if (!legacyDirectory.isDirectory) return
        legacyDirectory
            .listFiles()
            ?.filter { file -> file.isFile && file.extension == "json" }
            ?.forEach { oldFile ->
                val destination = File(offlineDirectory, oldFile.name)
                if (!destination.exists()) {
                    if (!oldFile.renameTo(destination)) {
                        oldFile.copyTo(destination, overwrite = false)
                        oldFile.delete()
                    }
                } else {
                    oldFile.delete()
                }
            }
        legacyDirectory.delete()
    }

    internal fun parse(
        json: String,
        referenceLatitude: Double,
        referenceLongitude: Double,
        datasetTimestampMillis: Long = 0L,
    ): List<NearbyOpenGatsoPoi> {
        val elements = JSONObject(json).getJSONArray("elements")
        val results = mutableListOf<NearbyOpenGatsoPoi>()
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
            val enforcement = tags.optString("enforcement")
            val type =
                when {
                    enforcement.contains("traffic_signals") -> OpenGatsoPoiType.RED_LIGHT_CAMERA
                    enforcement.contains("average_speed") -> OpenGatsoPoiType.AVERAGE_SPEED_CAMERA
                    else -> OpenGatsoPoiType.SPEED_CAMERA
                }
            val speedLimit = parseSpeedKph(tags.optString("maxspeed"))
            val description =
                sequenceOf(
                    tags.optString("name"),
                    tags.optString("operator"),
                    tags.optString("ref"),
                ).firstOrNull(String::isNotBlank).orEmpty()
            val poi =
                OpenGatsoPoi(
                    longitude = longitude,
                    latitude = latitude,
                    type = type,
                    speedLimitKph = speedLimit,
                    description = description,
                )
            results +=
                NearbyOpenGatsoPoi(
                    poi = poi,
                    distanceMeters =
                        OpenGatsoRepository.distanceMeters(
                            referenceLatitude,
                            referenceLongitude,
                            latitude,
                            longitude,
                        ),
                    sources = setOf(CameraDataSource.OPENSTREETMAP),
                    sourceRecords =
                        listOf(
                            CameraSourceRecord(
                                source = CameraDataSource.OPENSTREETMAP,
                                sourceId = "${element.optString("type", "object")}/${element.optLong("id")}",
                                rawType = enforcement.ifBlank { tags.optString("highway").ifBlank { null } },
                                roadName =
                                    tags.optString("road_name").ifBlank {
                                        tags.optString("name").ifBlank { null }
                                    },
                                direction =
                                    tags.optString("direction").ifBlank {
                                        tags.optString("camera:direction").ifBlank { null }
                                    },
                                operator = tags.optString("operator").ifBlank { null },
                                locationDescription = description.ifBlank { null },
                                installedDate = tags.optString("start_date").ifBlank { null },
                                sourceUpdatedAtMillis = datasetTimestampMillis.takeIf { it > 0L },
                            ),
                        ),
                )
        }
        return mergeCameraSources(emptyList(), results)
    }

    private fun overpassQuery(bounds: ExpandedBounds): String =
        String.format(
            Locale.US,
            "[out:json][timeout:15];(" +
                "node[\"highway\"=\"speed_camera\"](%.6f,%.6f,%.6f,%.6f);" +
                "nwr[\"type\"=\"enforcement\"][\"enforcement\"~\"maxspeed|average_speed|traffic_signals\"]" +
                "(%.6f,%.6f,%.6f,%.6f);" +
                ");out center tags;",
            bounds.south,
            bounds.west,
            bounds.north,
            bounds.east,
            bounds.south,
            bounds.west,
            bounds.north,
            bounds.east,
        )

    private fun parseSpeedKph(value: String): Int? {
        val numeric = SPEED_NUMBER.find(value)?.value?.toDoubleOrNull() ?: return null
        val kph = if (value.contains("mph", ignoreCase = true)) numeric * 1.609344 else numeric
        return kph.toInt().takeIf { it in 1..300 }
    }

    private fun osmDataTimestamp(json: String): Long =
        runCatching {
            val timestamp =
                JSONObject(json)
                    .optJSONObject("osm3s")
                    ?.optString("timestamp_osm_base")
                    .orEmpty()
            Instant.parse(timestamp).toEpochMilli()
        }.getOrDefault(0L)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun longitudeIsInside(
        longitude: Double,
        westLongitude: Double,
        eastLongitude: Double,
    ): Boolean =
        if (westLongitude <= eastLongitude) {
            longitude in westLongitude..eastLongitude
        } else {
            longitude >= westLongitude || longitude <= eastLongitude
        }

    private data class ExpandedBounds(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

    companion object {
        private val OVERPASS_ENDPOINTS =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
            )
        private const val OFFLINE_DATA_DIRECTORY = "camera-data"
        private const val OPENSTREETMAP_DIRECTORY = "openstreetmap"
        private const val LEGACY_CACHE_DIRECTORY = "osm_camera_queries"
        private const val CACHE_GRID_DEGREES = 0.1
        private const val CACHE_MAX_AGE_MILLIS = 2 * 60 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
        private val SPEED_NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
        private val QUERY_LOCK = Any()
    }
}

fun mergeCameraSources(
    first: List<NearbyOpenGatsoPoi>,
    second: List<NearbyOpenGatsoPoi>,
    duplicateDistanceMeters: Int = 30,
): List<NearbyOpenGatsoPoi> {
    val merged = first.toMutableList()
    second.forEach { candidate ->
        val duplicateIndex =
            merged.indexOfFirst { existing ->
                val distance =
                    OpenGatsoRepository.distanceMeters(
                        existing.poi.latitude,
                        existing.poi.longitude,
                        candidate.poi.latitude,
                        candidate.poi.longitude,
                    )
                distance <= duplicateDistanceMeters && camerasAreCompatible(existing, candidate)
            }
        if (duplicateIndex < 0) {
            merged += candidate
        } else {
            val existing = merged[duplicateIndex]
            val preferredPoi =
                when {
                    existing.poi.speedLimitKph == null && candidate.poi.speedLimitKph != null -> candidate.poi
                    existing.poi.type == OpenGatsoPoiType.SPEED_CAMERA &&
                        candidate.poi.type != OpenGatsoPoiType.SPEED_CAMERA -> candidate.poi
                    else -> existing.poi
                }
            merged[duplicateIndex] =
                existing.copy(
                    poi = preferredPoi,
                    distanceMeters = minOf(existing.distanceMeters, candidate.distanceMeters),
                    sources = existing.sources + candidate.sources,
                    sourceRecords =
                        (existing.sourceRecords + candidate.sourceRecords)
                            .distinctBy { record ->
                                listOf(
                                    record.source.name,
                                    record.sourceId,
                                    record.rawType,
                                    record.direction,
                                    record.roadName,
                                ).joinToString("|")
                            },
                )
        }
    }
    return merged.sortedBy(NearbyOpenGatsoPoi::distanceMeters)
}

private fun camerasAreCompatible(
    first: NearbyOpenGatsoPoi,
    second: NearbyOpenGatsoPoi,
): Boolean {
    val sameSource = first.sources.intersect(second.sources).isNotEmpty()
    if (sameSource) {
        val firstIds = first.sourceRecords.mapNotNull(CameraSourceRecord::sourceId).toSet()
        val secondIds = second.sourceRecords.mapNotNull(CameraSourceRecord::sourceId).toSet()
        if (firstIds.isEmpty() || secondIds.isEmpty()) return false
        if (firstIds.intersect(secondIds).isEmpty()) return false
    }
    if (first.poi.type != second.poi.type) return false
    val firstSpeed = first.poi.speedLimitKph
    val secondSpeed = second.poi.speedLimitKph
    return firstSpeed == null || secondSpeed == null || firstSpeed == secondSpeed
}
