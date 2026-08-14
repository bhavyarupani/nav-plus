package com.roadpulse.auto.terrain

import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ElevationSample(
    val coordinate: RoadCoordinate,
    val elevationMeters: Double,
)

enum class SlopeTrend {
    UPHILL,
    DOWNHILL,
    LEVEL,
}

data class ElevationProfileSummary(
    val currentElevationMeters: Int,
    val distanceMeters: Int,
    val elevationChangeMeters: Int,
    val averageGradePercent: Double,
    val trend: SlopeTrend,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
) {
    fun compactText(): String {
        val distance =
            if (distanceMeters >= 1_000) {
                String.format(Locale.US, "%.1f km", distanceMeters / 1_000.0)
            } else {
                "$distanceMeters m"
            }
        val slope =
            when (trend) {
                SlopeTrend.UPHILL -> String.format(Locale.US, "↗ %.1f%% uphill", averageGradePercent)
                SlopeTrend.DOWNHILL -> String.format(Locale.US, "↘ %.1f%% downhill", -averageGradePercent)
                SlopeTrend.LEVEL -> "↔ mostly level"
            }
        return "$currentElevationMeters m altitude · $slope ahead for $distance"
    }

    fun phoneText(): String {
        val sourceState = if (usedSavedData) "saved terrain" else "updated terrain"
        return "ROAD AHEAD · ${compactText()}\nDriving estimate · $sourceState · Open-Meteo/Copernicus"
    }
}

object ElevationRouteAnalyzer {
    fun sampleAhead(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
        horizonMeters: Double = 2_000.0,
        spacingMeters: Double = 250.0,
    ): List<RoadCoordinate> {
        val validRoute = route.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (validRoute.size < 2) return emptyList()
        val startIndex =
            current?.let { location ->
                validRoute.indices.minByOrNull { index -> distanceMeters(location, validRoute[index]) }
            } ?: 0
        val forward = validRoute.drop(startIndex ?: 0)
        if (forward.size < 2) return emptyList()

        val cumulative = MutableList(forward.size) { 0.0 }
        for (index in 1 until forward.size) {
            cumulative[index] = cumulative[index - 1] +
                distanceMeters(forward[index - 1], forward[index])
        }
        val availableDistance = minOf(horizonMeters, cumulative.last())
        if (availableDistance < MIN_PROFILE_DISTANCE_METERS) return emptyList()

        val targets =
            generateSequence(0.0) { previous -> previous + spacingMeters }
                .takeWhile { it < availableDistance }
                .toMutableList()
                .apply { add(availableDistance) }
        return targets
            .map { target -> interpolate(forward, cumulative, target) }
            .distinctBy { "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude) }
    }

    fun summarize(
        samples: List<ElevationSample>,
        timestampMillis: Long,
        usedSavedData: Boolean,
    ): ElevationProfileSummary? {
        if (samples.size < 2) return null
        val horizontalDistance =
            samples.zipWithNext().sumOf { (start, end) ->
                distanceMeters(start.coordinate, end.coordinate)
            }
        if (horizontalDistance < MIN_PROFILE_DISTANCE_METERS) return null
        val elevationChange = samples.last().elevationMeters - samples.first().elevationMeters
        val grade = (elevationChange / horizontalDistance * 100.0).coerceIn(-MAX_DISPLAY_GRADE, MAX_DISPLAY_GRADE)
        val trend =
            when {
                grade >= LEVEL_THRESHOLD_PERCENT -> SlopeTrend.UPHILL
                grade <= -LEVEL_THRESHOLD_PERCENT -> SlopeTrend.DOWNHILL
                else -> SlopeTrend.LEVEL
            }
        return ElevationProfileSummary(
            currentElevationMeters = samples.first().elevationMeters.roundToInt(),
            distanceMeters = horizontalDistance.roundToInt(),
            elevationChangeMeters = elevationChange.roundToInt(),
            averageGradePercent = grade,
            trend = trend,
            timestampMillis = timestampMillis,
            usedSavedData = usedSavedData,
        )
    }

    internal fun distanceMeters(
        start: RoadCoordinate,
        end: RoadCoordinate,
    ): Double {
        val latitude1 = Math.toRadians(start.latitude)
        val latitude2 = Math.toRadians(end.latitude)
        val deltaLatitude = latitude2 - latitude1
        val deltaLongitude = Math.toRadians(end.longitude - start.longitude)
        val haversine =
            sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
                cos(latitude1) * cos(latitude2) *
                sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private fun interpolate(
        route: List<RoadCoordinate>,
        cumulative: List<Double>,
        targetMeters: Double,
    ): RoadCoordinate {
        if (targetMeters <= 0.0) return route.first()
        val endIndex =
            cumulative.indexOfFirst { it >= targetMeters }.takeIf { it >= 1 }
                ?: return route.last()
        val startIndex = endIndex - 1
        val segmentDistance = cumulative[endIndex] - cumulative[startIndex]
        if (segmentDistance <= 0.0) return route[endIndex]
        val fraction = (targetMeters - cumulative[startIndex]) / segmentDistance
        return RoadCoordinate(
            latitude =
                route[startIndex].latitude +
                    (route[endIndex].latitude - route[startIndex].latitude) * fraction,
            longitude =
                route[startIndex].longitude +
                    (route[endIndex].longitude - route[startIndex].longitude) * fraction,
        )
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_PROFILE_DISTANCE_METERS = 100.0
    private const val LEVEL_THRESHOLD_PERCENT = 1.0
    private const val MAX_DISPLAY_GRADE = 30.0
}
