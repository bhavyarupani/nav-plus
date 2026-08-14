package com.roadpulse.auto.alerts

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Reader

data class SourcedCameraPoi(
    val poi: OpenGatsoPoi,
    val sourceRecord: CameraSourceRecord,
)

object OfficialCameraDataParser {
    fun parseFrance(reader: Reader): List<SourcedCameraPoi> =
        BufferedReader(reader).useLines { lines ->
            lines
                .drop(1)
                .mapNotNull { line ->
                    val fields = parseDelimitedFields(line, ';')
                    if (fields.size < 6) return@mapNotNull null
                    val sourceId = fields[0].trim().ifBlank { return@mapNotNull null }
                    val rawType = fields[1].trim()
                    val latitude = parseCoordinate(fields[4], -90.0, 90.0) ?: return@mapNotNull null
                    val longitude = parseCoordinate(fields[5], -180.0, 180.0) ?: return@mapNotNull null
                    val type =
                        when (rawType.uppercase()) {
                            "ETVM" -> OpenGatsoPoiType.AVERAGE_SPEED_CAMERA
                            "ETFR" -> OpenGatsoPoiType.RED_LIGHT_CAMERA
                            "ETPN" -> OpenGatsoPoiType.RAIL_CROSSING_CAMERA
                            else -> OpenGatsoPoiType.SPEED_CAMERA
                        }
                    val speed = fields[3].trim().toIntOrNull()?.takeIf { it in 1..300 }
                    SourcedCameraPoi(
                        poi =
                            OpenGatsoPoi(
                                longitude = longitude,
                                latitude = latitude,
                                type = type,
                                speedLimitKph = speed,
                                description = "French fixed camera $sourceId",
                            ),
                        sourceRecord =
                            CameraSourceRecord(
                                source = CameraDataSource.FRANCE_INTERIOR,
                                sourceId = sourceId,
                                rawType = rawType.ifBlank { null },
                                installedDate = fields[2].trim().ifBlank { null },
                                active = true,
                            ),
                    )
                }.toList()
        }

    fun parseBrussels(json: String): List<SourcedCameraPoi> =
        parseFeatures(json) { feature, properties ->
            val coordinates = pointCoordinates(feature) ?: return@parseFeatures null
            val active = properties.optBooleanOrNull("active")
            val sourceId =
                properties.optString("radar_id").ifBlank {
                    properties.optString("gid").ifBlank { feature.optString("id") }
                }
            val roadName = properties.firstNonBlank("street_fr", "street_nl")
            val areaName = properties.firstNonBlank("mu_fr", "mu_nl")
            val direction = properties.firstNonBlank("direction_fr", "direction_nl")
            val description = properties.firstNonBlank("descr_fr", "descr_nl")
            SourcedCameraPoi(
                poi =
                    OpenGatsoPoi(
                        longitude = coordinates.first,
                        latitude = coordinates.second,
                        type = OpenGatsoPoiType.SPEED_CAMERA,
                        speedLimitKph = null,
                        description = listOfNotNull(roadName, description).distinct().joinToString(" · "),
                    ),
                sourceRecord =
                    CameraSourceRecord(
                        source = CameraDataSource.BRUSSELS_MOBILITY,
                        sourceId = sourceId.ifBlank { null },
                        rawType = properties.optString("radar_type").ifBlank { null },
                        roadName = roadName,
                        direction = direction,
                        areaName = areaName,
                        locationDescription = description,
                        installedDate = properties.optString("date_installation").ifBlank { null },
                        active = active,
                    ),
            )
        }

    fun parseLuxembourg(json: String): List<SourcedCameraPoi> =
        parseFeatures(json) { feature, properties ->
            val coordinates = pointCoordinates(feature) ?: return@parseFeatures null
            val sourceId =
                properties.optString("ID").ifBlank {
                    properties.optString("OBJECTID_1")
                }
            val roadName = properties.optString("TRANCON").ifBlank { null }
            val direction =
                listOf(
                    properties.optString("DIR"),
                    properties.optString("DIR_"),
                ).filter(String::isNotBlank).distinct().joinToString(" ↔ ").ifBlank { null }
            SourcedCameraPoi(
                poi =
                    OpenGatsoPoi(
                        longitude = coordinates.first,
                        latitude = coordinates.second,
                        type = OpenGatsoPoiType.SPEED_CAMERA,
                        speedLimitKph = null,
                        description = roadName.orEmpty(),
                    ),
                sourceRecord =
                    CameraSourceRecord(
                        source = CameraDataSource.LUXEMBOURG_PCH,
                        sourceId = sourceId.ifBlank { null },
                        rawType = "fixed",
                        roadName = roadName,
                        direction = direction,
                        installedDate = properties.optString("YEAR").ifBlank { null },
                        active = true,
                    ),
            )
        }

    private fun parseFeatures(
        json: String,
        mapper: (JSONObject, JSONObject) -> SourcedCameraPoi?,
    ): List<SourcedCameraPoi> {
        val features = JSONObject(json).getJSONArray("features")
        require(features.length() <= MAXIMUM_FEATURES) { "Official camera feed exceeds safety limit" }
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                mapper(feature, properties)?.let(::add)
            }
        }
    }

    private fun pointCoordinates(feature: JSONObject): Pair<Double, Double>? {
        val geometry = feature.optJSONObject("geometry") ?: return null
        if (!geometry.optString("type").equals("Point", ignoreCase = true)) return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        val longitude = coordinates.optDouble(0, Double.NaN)
        val latitude = coordinates.optDouble(1, Double.NaN)
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        return longitude to latitude
    }

    private fun parseCoordinate(
        value: String,
        minimum: Double,
        maximum: Double,
    ): Double? =
        value
            .trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it in minimum..maximum }

    internal fun parseDelimitedFields(
        line: String,
        delimiter: Char,
    ): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index += 1
        }
        fields += current.toString()
        return fields
    }

    private fun JSONObject.firstNonBlank(vararg names: String): String? =
        names
            .asSequence()
            .map(::optString)
            .firstOrNull(String::isNotBlank)

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        when {
            !has(name) || isNull(name) -> null
            else -> optBoolean(name)
        }

    private const val MAXIMUM_FEATURES = 50_000
}
