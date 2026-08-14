package com.roadpulse.auto.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class DwdRoadWeatherRepository private constructor(
    private val appContext: Context?,
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() },
    )

    internal constructor(cacheDirectory: File) : this(null, cacheDirectory)

    fun roadForecastNear(
        latitude: Double,
        longitude: Double,
        nowMillis: Long = System.currentTimeMillis(),
    ): RoadWeatherResult {
        val file = File(cacheDirectory, FORECAST_FILE)
        var usedSaved = false
        val fresh = file.isFile && nowMillis - file.lastModified() <= FORECAST_CACHE_AGE_MILLIS
        if (!fresh && hasValidatedInternet()) {
            runCatching { downloadToFile(FORECAST_URL, file, MAX_FORECAST_BYTES) }
                .onFailure {
                    check(file.isFile) { "DWD road-weather download unavailable" }
                    usedSaved = true
                }
        } else {
            usedSaved = file.isFile
        }
        check(file.isFile) { "No saved DWD road-weather forecast" }
        return parseForecast(file, latitude, longitude, nowMillis).copy(usedSavedData = usedSaved)
    }

    fun warningsInBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): WeatherWarningResult {
        val grid =
            listOf(
                floor(south * 10.0) / 10.0,
                floor(west * 10.0) / 10.0,
                ceil(north * 10.0) / 10.0,
                ceil(east * 10.0) / 10.0,
            )
        val bbox = grid.joinToString(",") + ",EPSG:4326"
        val url =
            "$WARNING_WFS?service=WFS&version=2.0.0&request=GetFeature&" +
                "typeName=dwd%3AWarnungen_Gemeinden_vereinigt&outputFormat=application%2Fjson&" +
                "srsName=EPSG%3A4326&bbox=${URLEncoder.encode(bbox, Charsets.UTF_8.name())}"
        val file = File(cacheDirectory, "warning-${sha256(bbox)}.json")
        var usedSaved = false
        val fresh =
            file.isFile &&
                System.currentTimeMillis() - file.lastModified() <= WARNING_CACHE_AGE_MILLIS
        val json =
            when {
                fresh -> {
                    usedSaved = true
                    file.readText()
                }
                hasValidatedInternet() ->
                    runCatching { downloadText(url, MAX_WARNING_BYTES) }.fold(
                        onSuccess = { downloaded ->
                            JSONObject(downloaded)
                            saveAtomically(file, downloaded)
                            downloaded
                        },
                        onFailure = { error ->
                            if (!file.isFile) throw error
                            usedSaved = true
                            file.readText()
                        },
                    )
                file.isFile -> {
                    usedSaved = true
                    file.readText()
                }
                else -> error("No saved DWD warnings for this area")
            }
        return parseWarnings(json, file.lastModified()).copy(usedSavedData = usedSaved)
    }

    internal fun parseForecast(
        file: File,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
    ): RoadWeatherResult {
        var generatedAt = 0L
        var closestDistance = Double.MAX_VALUE
        var closestStation = ""
        val selected = mutableListOf<RoadWeatherForecast>()
        BZip2CompressorInputStream(file.inputStream().buffered()).use { compressed ->
            BufferedReader(InputStreamReader(compressed, Charsets.UTF_8)).use { reader ->
                val columns = reader.readLine()?.split(';').orEmpty()
                val columnIndex = columns.withIndex().associate { it.value to it.index }
                generatedAt = parseUtc(reader.readLine().orEmpty()) ?: 0L
                reader.lineSequence().forEach { line ->
                    val fields = line.split(';')
                    val stationId = fields.value(columnIndex, "ID") ?: return@forEach
                    val pointLatitude =
                        fields.value(columnIndex, "Lat")?.toDoubleOrNull()
                            ?: return@forEach
                    val pointLongitude =
                        fields.value(columnIndex, "Lon")?.toDoubleOrNull()
                            ?: return@forEach
                    val distance =
                        com.roadpulse.auto.alerts.OpenGatsoRepository
                            .distanceMeters(
                                latitude,
                                longitude,
                                pointLatitude,
                                pointLongitude,
                            ).toDouble()
                    if (stationId != closestStation && distance < closestDistance) {
                        closestStation = stationId
                        closestDistance = distance
                        selected.clear()
                    }
                    if (stationId != closestStation) return@forEach
                    val forecastAt =
                        parseUtc(fields.value(columnIndex, "YYYYMMDDHHmm").orEmpty())
                            ?: return@forEach
                    if (forecastAt < nowMillis - ONE_HOUR_MILLIS ||
                        forecastAt > nowMillis + FORECAST_HORIZON_MILLIS
                    ) {
                        return@forEach
                    }
                    selected +=
                        RoadWeatherForecast(
                            stationId = stationId,
                            coordinate = RoadCoordinate(pointLatitude, pointLongitude),
                            forecastAtMillis = forecastAt,
                            airTemperatureC = fields.doubleValue(columnIndex, "TL"),
                            surfaceTemperatureC = fields.doubleValue(columnIndex, "TS"),
                            dewPointC = fields.doubleValue(columnIndex, "TD"),
                            liquidPrecipitationMm = fields.doubleValue(columnIndex, "RRL1c"),
                            solidPrecipitationMm = fields.doubleValue(columnIndex, "RRS1c"),
                            rainProbabilityPercent = fields.doubleValue(columnIndex, "WWL6"),
                            snowProbabilityPercent = fields.doubleValue(columnIndex, "WWS3"),
                            condition = parseRoadCondition(fields.value(columnIndex, "RC")),
                        )
                }
            }
        }
        check(selected.isNotEmpty()) { "No current DWD road-weather station was found" }
        return RoadWeatherResult(
            stationDistanceMeters = closestDistance.toInt(),
            forecasts = selected.sortedBy(RoadWeatherForecast::forecastAtMillis),
            generatedAtMillis = generatedAt,
            usedSavedData = false,
        )
    }

    internal fun parseWarnings(
        json: String,
        timestampMillis: Long,
    ): WeatherWarningResult {
        val features = JSONObject(json).optJSONArray("features") ?: JSONArray()
        val warnings =
            buildList {
                for (index in 0 until features.length()) {
                    val feature = features.optJSONObject(index) ?: continue
                    val properties = feature.optJSONObject("properties") ?: JSONObject()
                    val coordinate = geometryCentre(feature.optJSONObject("geometry")) ?: continue
                    add(
                        WeatherWarning(
                            id =
                                feature.optString("id").ifBlank {
                                    properties.stringFrom("IDENTIFIER", "WARNCELLID", "id")
                                },
                            coordinate = coordinate,
                            event =
                                properties.stringFrom("EVENT", "event").ifBlank {
                                    "Weather warning"
                                },
                            headline = properties.stringFrom("HEADLINE", "headline", "EVENT"),
                            description =
                                properties.stringFrom(
                                    "DESCRIPTION",
                                    "description",
                                    "INSTRUCTION",
                                ),
                            severity = properties.stringFrom("SEVERITY", "severity", "EC_GROUP"),
                            beginsAtMillis = parseIso(properties.stringFrom("ONSET", "onset")),
                            expiresAtMillis = parseIso(properties.stringFrom("EXPIRES", "expires")),
                        ),
                    )
                }
            }
        return WeatherWarningResult(warnings, timestampMillis, false)
    }

    private fun geometryCentre(geometry: JSONObject?): RoadCoordinate? {
        val coordinates = geometry?.optJSONArray("coordinates") ?: return null
        val points = mutableListOf<RoadCoordinate>()
        collectCoordinates(coordinates, points)
        if (points.isEmpty()) return null
        return RoadCoordinate(
            latitude = points.map(RoadCoordinate::latitude).average(),
            longitude = points.map(RoadCoordinate::longitude).average(),
        )
    }

    private fun collectCoordinates(
        array: JSONArray,
        output: MutableList<RoadCoordinate>,
    ) {
        if (array.length() >= 2 && array.opt(0) is Number && array.opt(1) is Number) {
            output += RoadCoordinate(array.optDouble(1), array.optDouble(0))
            return
        }
        for (index in 0 until array.length()) {
            array.optJSONArray(index)?.let { collectCoordinates(it, output) }
        }
    }

    private fun downloadToFile(
        url: String,
        destination: File,
        maximumBytes: Long,
    ) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val connection = openConnection(url)
        try {
            if (connection.responseCode !in 200..299) error("DWD returned HTTP ${connection.responseCode}")
            val declared = connection.contentLengthLong
            check(declared <= 0L || declared <= maximumBytes) { "DWD forecast is unexpectedly large" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= maximumBytes) { "DWD forecast exceeded its size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            BZip2CompressorInputStream(temporary.inputStream().buffered()).use { stream ->
                check(stream.read() >= 0) { "DWD forecast is empty" }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            connection.disconnect()
            if (temporary.exists() && !destination.exists()) temporary.delete()
        }
    }

    private fun downloadText(
        url: String,
        maximumBytes: Long,
    ): String {
        val connection = openConnection(url)
        return try {
            if (connection.responseCode !in 200..299) error("DWD returned HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val bytes = input.readNBytes((maximumBytes + 1).toInt())
                check(bytes.size <= maximumBytes) { "DWD warnings exceeded their size limit" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun parseRoadCondition(value: String?): RoadSurfaceCondition =
        when (value?.toIntOrNull()) {
            1 -> RoadSurfaceCondition.DRY
            2 -> RoadSurfaceCondition.DAMP
            3 -> RoadSurfaceCondition.SNOW
            4 -> RoadSurfaceCondition.FROST
            5 -> RoadSurfaceCondition.FREEZING_WETNESS
            6 -> RoadSurfaceCondition.BLACK_ICE
            else -> RoadSurfaceCondition.UNKNOWN
        }

    private fun parseUtc(value: String): Long? =
        runCatching {
            LocalDateTime.parse(value.trim(), UTC_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()

    private fun parseIso(value: String): Long? =
        runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.recoverCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun List<String>.value(
        indices: Map<String, Int>,
        key: String,
    ): String? = indices[key]?.let(::getOrNull)?.trim()?.takeUnless { it.isBlank() || it.startsWith("--") }

    private fun List<String>.doubleValue(
        indices: Map<String, Int>,
        key: String,
    ): Double? = value(indices, key)?.replace(',', '.')?.toDoubleOrNull()

    private fun JSONObject.stringFrom(vararg keys: String): String =
        keys
            .asSequence()
            .map(::optString)
            .firstOrNull(String::isNotBlank)
            .orEmpty()

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

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    companion object {
        const val ATTRIBUTION = "Road weather and warnings: Deutscher Wetterdienst (DWD)"
        private const val FORECAST_URL =
            "https://opendata.dwd.de/weather/local_forecasts/swsmos/swsmos_LATEST_opendata.csv.bz2"
        private const val WARNING_WFS = "https://maps.dwd.de/geoserver/dwd/ows"
        private const val CACHE_DIRECTORY = "road-data/dwd"
        private const val FORECAST_FILE = "swsmos-latest.csv.bz2"
        private const val FORECAST_CACHE_AGE_MILLIS = 65 * 60_000L
        private const val WARNING_CACHE_AGE_MILLIS = 10 * 60_000L
        private const val FORECAST_HORIZON_MILLIS = 24 * 60 * 60_000L
        private const val ONE_HOUR_MILLIS = 60 * 60_000L
        private const val MAX_FORECAST_BYTES = 6_000_000L
        private const val MAX_WARNING_BYTES = 5_000_000L
        private const val CONNECT_TIMEOUT_MILLIS = 12_000
        private const val READ_TIMEOUT_MILLIS = 40_000
        private const val USER_AGENT = "RoadPulse/0.1 personal Android navigation prototype"
        private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.ROOT)
    }
}
