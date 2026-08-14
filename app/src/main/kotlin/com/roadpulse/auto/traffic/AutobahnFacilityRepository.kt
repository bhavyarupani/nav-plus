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
import java.util.Locale

class AutobahnFacilityRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor() : this(null, File("."))

    fun facilitiesForRoads(roadIds: Set<String>): RoadFacilityResult {
        val roads =
            roadIds
                .asSequence()
                .map { it.replace(" ", "").uppercase(Locale.ROOT) }
                .filter(AUTOBAHN_REF::matches)
                .distinct()
                .take(MAX_ROADS_PER_REFRESH)
                .toList()
        var newest = 0L
        var usedSaved = false
        val facilities = mutableListOf<RoadFacility>()
        roads.forEach { roadId ->
            SERVICES.forEach { service ->
                runCatching {
                    val cacheFile = File(cacheDirectory, "${roadId.lowercase()}-${service.path}.json")
                    load(roadId, service, cacheFile)
                }.onSuccess { response ->
                    newest = maxOf(newest, response.timestampMillis)
                    usedSaved = usedSaved || response.usedSavedData
                    facilities += parse(response.json, roadId, service)
                }
            }
        }
        return RoadFacilityResult(
            facilities = facilities.distinctBy(RoadFacility::id),
            timestampMillis = newest,
            usedSavedData = usedSaved,
        )
    }

    internal fun parse(
        json: String,
        roadId: String,
        service: Service,
    ): List<RoadFacility> {
        val array = JSONObject(json).optJSONArray(service.responseKey) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("future", false)) continue
                val coordinate = item.optJSONObject("coordinate") ?: continue
                val latitude = coordinate.optDouble("lat", Double.NaN)
                val longitude = coordinate.optDouble("long", Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue
                val description = item.optJSONArray("description").nonBlankStrings()
                val amenities =
                    item
                        .optJSONArray("lorryParkingFeatureIcons")
                        .objectStrings("description")
                        .filterNot { it.contains("notAvailable", ignoreCase = true) }
                val maximumPower =
                    description
                        .asSequence()
                        .mapNotNull {
                            POWER
                                .find(it)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                        }.maxOrNull()
                val restroomMentioned =
                    (description + amenities).any { text ->
                        RESTROOM_TERMS.any { term -> text.contains(term, ignoreCase = true) }
                    }
                val restroomFee = restroomFeeStatus(description + amenities)
                val facility =
                    RoadFacility(
                        id =
                            item.optString("identifier").ifBlank {
                                "$roadId-${service.path}-$latitude-$longitude"
                            },
                        roadId = roadId,
                        type = service.type,
                        coordinate = RoadCoordinate(latitude, longitude),
                        title = item.optString("title").ifBlank { service.type.displayName },
                        subtitle = item.optString("subtitle").trim(),
                        detail =
                            (description + amenities)
                                .distinct()
                                .joinToString(" · ")
                                .ifBlank { service.type.displayName },
                        maximumChargingPowerKw = maximumPower,
                        lorrySpaces = description.firstNumberAfter("LKW Stellplätze"),
                        carSpaces = description.firstNumberAfter("PKW Stellplätze"),
                        amenities = amenities,
                    )
                add(facility)
                if (
                    service == Service.PARKING &&
                    restroomMentioned
                ) {
                    val feeLabel =
                        when (restroomFee) {
                            RestroomFeeStatus.FREE -> "free"
                            RestroomFeeStatus.PAID -> "paid"
                            RestroomFeeStatus.UNKNOWN -> "fee not published"
                        }
                    add(
                        facility.copy(
                            id = "${facility.id}-restroom",
                            type = RoadFacilityType.RESTROOM,
                            title = "${facility.title} WC",
                            detail = "Highway restroom · $feeLabel · ${facility.detail}",
                            restroomFeeStatus = restroomFee,
                        ),
                    )
                }
            }
        }
    }

    private fun load(
        roadId: String,
        service: Service,
        file: File,
    ): CachedResponse {
        val fresh =
            file.isFile &&
                System.currentTimeMillis() - file.lastModified() <= CACHE_MAX_AGE_MILLIS
        if (fresh) return CachedResponse(file.readText(), file.lastModified(), true)
        if (!hasValidatedInternet()) {
            check(file.isFile) { "No saved ${service.type.displayName} data for $roadId" }
            return CachedResponse(file.readText(), file.lastModified(), true)
        }
        return runCatching {
            val encodedRoad = URLEncoder.encode(roadId, Charsets.UTF_8.name())
            val connection =
                URL("$BASE_URL/$encodedRoad/services/${service.path}")
                    .openConnection() as HttpURLConnection
            val json =
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    if (connection.responseCode !in 200..299) {
                        error("Autobahn API returned HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            JSONObject(json)
            saveAtomically(file, json)
            CachedResponse(json, file.lastModified(), false)
        }.getOrElse { error ->
            if (!file.isFile) throw error
            CachedResponse(file.readText(), file.lastModified(), true)
        }
    }

    private fun JSONArray?.nonBlankStrings(): List<String> =
        buildList {
            val array = this@nonBlankStrings ?: return@buildList
            for (index in 0 until array.length()) {
                array
                    .optString(index)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }

    private fun JSONArray?.objectStrings(key: String): List<String> =
        buildList {
            val array = this@objectStrings ?: return@buildList
            for (index in 0 until array.length()) {
                array
                    .optJSONObject(index)
                    ?.optString(key)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }

    private fun List<String>.firstNumberAfter(label: String): Int? =
        asSequence()
            .firstOrNull { it.startsWith(label, ignoreCase = true) }
            ?.let { NUMBER.find(it)?.value?.toIntOrNull() }

    private fun restroomFeeStatus(text: List<String>): RestroomFeeStatus {
        val combined = text.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            FREE_RESTROOM_TERMS.any(combined::contains) -> RestroomFeeStatus.FREE
            PAID_RESTROOM_TERMS.any(combined::contains) -> RestroomFeeStatus.PAID
            else -> RestroomFeeStatus.UNKNOWN
        }
    }

    private fun saveAtomically(
        file: File,
        value: String,
    ) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value)
        if (!temporary.renameTo(file)) {
            file.writeText(value)
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
        val type: RoadFacilityType,
    ) {
        WEBCAM("webcam", "webcam", RoadFacilityType.WEBCAM),
        PARKING("parking_lorry", "parking_lorry", RoadFacilityType.PARKING),
        CHARGING(
            "electric_charging_station",
            "electric_charging_station",
            RoadFacilityType.CHARGING,
        ),
    }

    private data class CachedResponse(
        val json: String,
        val timestampMillis: Long,
        val usedSavedData: Boolean,
    )

    companion object {
        private const val BASE_URL = "https://verkehr.autobahn.de/o/autobahn"
        private const val CACHE_DIRECTORY = "road-data/autobahn-facilities"
        private const val CACHE_MAX_AGE_MILLIS = 30 * 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_ROADS_PER_REFRESH = 6
        private const val USER_AGENT = "RoadPulse/0.1 personal Android navigation prototype"
        private val AUTOBAHN_REF = Regex("A[0-9]{1,3}[A-Z]?")
        private val SERVICES = Service.entries
        private val POWER = Regex("([0-9]{1,4})\\s*kW", RegexOption.IGNORE_CASE)
        private val NUMBER = Regex("[0-9]+")
        private val RESTROOM_TERMS = listOf("WC", "Toilet", "Sanifair")
        private val FREE_RESTROOM_TERMS = listOf("kostenlos", "gebührenfrei", "free toilet")
        private val PAID_RESTROOM_TERMS =
            listOf(
                "gebührenpflichtig",
                "kostenpflichtig",
                "sanifair",
                "paid toilet",
            )
    }
}

val RoadFacilityType.displayName: String
    get() =
        when (this) {
            RoadFacilityType.WEBCAM -> "Road webcam"
            RoadFacilityType.PARKING -> "Rest area / parking"
            RoadFacilityType.CHARGING -> "EV charging"
            RoadFacilityType.RESTROOM -> "Highway restroom"
        }
