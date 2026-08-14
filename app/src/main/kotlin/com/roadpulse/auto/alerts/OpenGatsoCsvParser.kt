package com.roadpulse.auto.alerts

import java.io.BufferedReader
import java.io.Reader

object OpenGatsoCsvParser {
    private val speedPattern = Regex("@([0-9]{1,3})")

    fun parse(reader: Reader): List<OpenGatsoPoi> =
        BufferedReader(reader).useLines { lines ->
            lines.mapNotNull(::parseLine).toList()
        }

    fun countValid(
        reader: Reader,
        maximum: Int = 100_000,
    ): Int {
        require(maximum > 0)
        var count = 0
        BufferedReader(reader).useLines { lines ->
            for (line in lines) {
                if (parseLine(line) != null) {
                    count += 1
                    require(count <= maximum) { "Open-GATSO dataset exceeds the safety limit" }
                }
            }
        }
        return count
    }

    fun parseLine(line: String): OpenGatsoPoi? {
        val fields = parseCsvFields(line)
        if (fields.size < 4) return null

        val longitude = fields[0].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        val latitude = fields[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val rawType = fields[2].trim().lowercase()
        val type =
            when {
                rawType.startsWith("average") -> OpenGatsoPoiType.AVERAGE_SPEED_CAMERA
                rawType.startsWith("max") -> OpenGatsoPoiType.SPEED_CAMERA
                rawType == "stop" -> OpenGatsoPoiType.RED_LIGHT_CAMERA
                rawType == "tunnel" -> OpenGatsoPoiType.TUNNEL
                rawType == "railroad" || rawType == "railway" -> OpenGatsoPoiType.RAIL_CROSSING
                else -> OpenGatsoPoiType.OTHER_ROAD_HAZARD
            }
        val speedLimit =
            speedPattern
                .find(rawType)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        return OpenGatsoPoi(
            longitude = longitude,
            latitude = latitude,
            type = type,
            speedLimitKph = speedLimit,
            description = fields.drop(3).joinToString(",").trim(),
        )
    }

    private fun parseCsvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        fields += current.toString().trim()
        return fields
    }
}
