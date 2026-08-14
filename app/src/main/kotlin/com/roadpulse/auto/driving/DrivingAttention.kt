package com.roadpulse.auto.driving

import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.terrain.ElevationProfileSummary
import com.roadpulse.auto.terrain.SlopeTrend
import com.roadpulse.auto.traffic.RoadInfrastructureType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

enum class RoadAheadEventType {
    ROAD_CONTROL,
    SPEED_CHANGE,
    CAMERA,
    TERRAIN,
}

enum class RouteMatchConfidence {
    HIGH,
    MEDIUM,
}

data class RoadAheadEvent(
    val id: String,
    val type: RoadAheadEventType,
    val title: String,
    val detail: String,
    val distanceMeters: Int,
    val priority: Int,
    val confidence: RouteMatchConfidence = RouteMatchConfidence.MEDIUM,
)

data class RoadAheadPresentation(
    val primary: RoadAheadEvent?,
    val secondary: RoadAheadEvent?,
) {
    val primaryText: String
        get() = primary?.let { "${it.title} · ${it.detail}" } ?: "Route clear ahead"

    val secondaryText: String?
        get() = secondary?.let { "${it.title} · ${it.detail}" }

    fun compactText(): String? =
        listOfNotNull(primaryText, secondaryText)
            .takeIf { primary != null }
            ?.joinToString(" · ")
}

/**
 * Turns the many RoadPulse feeds into one stable, glanceable queue. The map may retain every
 * route-matched marker, but a moving driver sees no more than two ranked upcoming events.
 */
object DrivingAttention {
    fun build(
        speedLimit: SpeedLimitAheadSummary?,
        roadFeatures: List<UpcomingRouteRoadFeature>,
        cameras: RouteCameraSnapshot,
        terrain: ElevationProfileSummary?,
        maneuverDistanceMeters: Int? = null,
    ): RoadAheadPresentation {
        val events =
            buildList {
                roadFeatures.take(MAX_CANDIDATES_PER_FEED).forEach { feature ->
                    add(feature.toRoadAheadEvent())
                }
                speedLimit?.takeIf { it.nextLimitKph != null || it.nextUnlimited }?.let { add(it.toEvent()) }
                if (cameras.blockedReason == null) {
                    cameras.cameras.take(MAX_CANDIDATES_PER_FEED).forEach { add(it.toEvent()) }
                }
                terrain
                    ?.takeIf {
                        it.trend != SlopeTrend.LEVEL && abs(it.averageGradePercent) >= MIN_TERRAIN_GRADE
                    }?.let { add(it.toEvent()) }
            }.filter { it.distanceMeters >= 0 }
                .distinctBy(RoadAheadEvent::id)
                .sortedWith(
                    compareByDescending<RoadAheadEvent> { it.priority + urgencyBonus(it.distanceMeters) }
                        .thenBy(RoadAheadEvent::distanceMeters),
                )

        val turnIsClose = maneuverDistanceMeters != null && maneuverDistanceMeters <= MANEUVER_FOCUS_METERS
        val visible =
            if (turnIsClose) {
                events.filter { event ->
                    event.priority >= CRITICAL_PRIORITY || event.distanceMeters <= IMMEDIATE_EVENT_METERS
                }
            } else {
                events
            }
        val primary = visible.firstOrNull()
        val secondary =
            if (turnIsClose) {
                null
            } else {
                visible.drop(1).firstOrNull { candidate ->
                    primary == null ||
                        candidate.type != primary.type ||
                        candidate.distanceMeters - primary.distanceMeters >= MIN_REPEAT_DISTANCE_METERS
                }
            }
        return RoadAheadPresentation(primary, secondary)
    }

    private fun UpcomingRouteRoadFeature.toRoadAheadEvent(): RoadAheadEvent {
        val basePriority =
            when (point.type) {
                RoadInfrastructureType.STOP_SIGN,
                RoadInfrastructureType.GIVE_WAY_SIGN,
                RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
                RoadInfrastructureType.RAILWAY_CROSSING,
                RoadInfrastructureType.TRAFFIC_RESTRICTION,
                RoadInfrastructureType.DIMENSION_RESTRICTION,
                -> 400
                RoadInfrastructureType.TRAFFIC_SIGNAL,
                RoadInfrastructureType.PEDESTRIAN_CROSSING,
                RoadInfrastructureType.SCHOOL_ZONE,
                RoadInfrastructureType.SURFACE_HAZARD,
                RoadInfrastructureType.TOLL,
                -> 350
                RoadInfrastructureType.ROAD_RULE_START,
                RoadInfrastructureType.ROAD_RULE_END,
                RoadInfrastructureType.PRIORITY_ROAD_SIGN,
                RoadInfrastructureType.TRAFFIC_CALMING,
                RoadInfrastructureType.TUNNEL,
                RoadInfrastructureType.MOTORWAY_JUNCTION,
                -> 300
                RoadInfrastructureType.SPEED_LIMIT_SIGN -> 240
                RoadInfrastructureType.BRIDGE -> 170
                RoadInfrastructureType.STEEP_GRADE,
                RoadInfrastructureType.OTHER_SIGN,
                -> 130
            }
        return RoadAheadEvent(
            id = "road:${point.id}",
            type = RoadAheadEventType.ROAD_CONTROL,
            title = point.title,
            detail = distanceLabel(distanceMeters),
            distanceMeters = distanceMeters,
            priority = basePriority,
            confidence = confidence,
        )
    }

    private fun SpeedLimitAheadSummary.toEvent(): RoadAheadEvent {
        val next =
            when {
                nextUnlimited -> "No fixed limit"
                nextLimitKph != null -> "Speed limit $nextLimitKph"
                else -> "Speed-limit change"
            }
        val eta =
            secondsAtCurrentSpeed
                ?.let { seconds ->
                    val minutes = (seconds + 59) / 60
                    " · about $minutes min"
                }.orEmpty()
        return RoadAheadEvent(
            id = "speed:$nextLimitKph:$nextUnlimited:$distanceMeters",
            type = RoadAheadEventType.SPEED_CHANGE,
            title = next,
            detail = "in ${distanceLabel(distanceMeters)}$eta",
            distanceMeters = distanceMeters,
            priority = 280,
            confidence = RouteMatchConfidence.MEDIUM,
        )
    }

    private fun UpcomingRouteCamera.toEvent(): RoadAheadEvent {
        val title =
            when (camera.poi.type) {
                OpenGatsoPoiType.SPEED_CAMERA -> "Speed camera"
                OpenGatsoPoiType.AVERAGE_SPEED_CAMERA -> "Average-speed camera"
                OpenGatsoPoiType.RED_LIGHT_CAMERA -> "Red-light camera"
                OpenGatsoPoiType.RAIL_CROSSING_CAMERA -> "Rail-crossing camera"
                else -> "Road camera"
            }
        val limit =
            camera.poi.speedLimitKph
                ?.let { " · $it km/h" }
                .orEmpty()
        return RoadAheadEvent(
            id = "camera:$id",
            type = RoadAheadEventType.CAMERA,
            title = title,
            detail = "${distanceLabel(distanceMeters)}$limit",
            distanceMeters = distanceMeters,
            priority = 320,
            confidence = confidence,
        )
    }

    private fun ElevationProfileSummary.toEvent(): RoadAheadEvent {
        val title =
            when (trend) {
                SlopeTrend.UPHILL -> String.format(Locale.US, "Uphill %.1f%%", averageGradePercent)
                SlopeTrend.DOWNHILL -> String.format(Locale.US, "Downhill %.1f%%", -averageGradePercent)
                SlopeTrend.LEVEL -> "Mostly level"
            }
        return RoadAheadEvent(
            id = "terrain:$trend:${averageGradePercent.toInt()}",
            type = RoadAheadEventType.TERRAIN,
            title = title,
            detail = "next ${distanceLabel(distanceMeters)} · $currentElevationMeters m altitude",
            distanceMeters = distanceMeters,
            priority = if (abs(averageGradePercent) >= STEEP_TERRAIN_GRADE) 260 else 120,
            confidence = RouteMatchConfidence.MEDIUM,
        )
    }

    private fun urgencyBonus(distanceMeters: Int): Int =
        when {
            distanceMeters <= 150 -> 120
            distanceMeters <= 500 -> 70
            distanceMeters <= 1_000 -> 35
            distanceMeters <= 2_500 -> 10
            else -> 0
        }

    private fun distanceLabel(distanceMeters: Int): String =
        if (distanceMeters >= 1_000) {
            String.format(Locale.GERMANY, "%.1f km", distanceMeters / 1_000.0)
        } else {
            "$distanceMeters m"
        }

    private const val MAX_CANDIDATES_PER_FEED = 6
    private const val MANEUVER_FOCUS_METERS = 350
    private const val IMMEDIATE_EVENT_METERS = 180
    private const val CRITICAL_PRIORITY = 350
    private const val MIN_REPEAT_DISTANCE_METERS = 250
    private const val MIN_TERRAIN_GRADE = 2.5
    private const val STEEP_TERRAIN_GRADE = 6.0
}

internal enum class DirectionMatch {
    MATCH,
    MISMATCH,
    UNKNOWN,
}

/** Interprets the numeric and compass bearings commonly used by OpenStreetMap data. */
internal object RouteDirectionMatcher {
    fun evaluate(
        routeBearingDegrees: Double,
        rawDirections: List<String?>,
    ): DirectionMatch {
        val bearings =
            rawDirections
                .filterNotNull()
                .flatMap { raw -> raw.split(';', ',', '|') }
                .mapNotNull(::parseBearing)
        if (bearings.isEmpty()) return DirectionMatch.UNKNOWN
        return if (bearings.any { angularDifference(routeBearingDegrees, it) <= MAX_DIFFERENCE_DEGREES }) {
            DirectionMatch.MATCH
        } else {
            DirectionMatch.MISMATCH
        }
    }

    fun bearing(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
    ): Double {
        val startLat = Math.toRadians(startLatitude)
        val endLat = Math.toRadians(endLatitude)
        val deltaLon = Math.toRadians(endLongitude - startLongitude)
        val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(endLat)
        val x =
            kotlin.math.cos(startLat) * kotlin.math.sin(endLat) -
                kotlin.math.sin(startLat) * kotlin.math.cos(endLat) * kotlin.math.cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun parseBearing(value: String): Double? {
        val normalized =
            value
                .trim()
                .lowercase(Locale.ROOT)
                .removeSuffix("°")
                .replace("degrees", "")
                .trim()
        normalized.toDoubleOrNull()?.let { return (it % 360.0 + 360.0) % 360.0 }
        return when (normalized) {
            "n", "north" -> 0.0
            "ne", "northeast", "north-east" -> 45.0
            "e", "east" -> 90.0
            "se", "southeast", "south-east" -> 135.0
            "s", "south" -> 180.0
            "sw", "southwest", "south-west" -> 225.0
            "w", "west" -> 270.0
            "nw", "northwest", "north-west" -> 315.0
            else -> null
        }
    }

    private fun angularDifference(
        first: Double,
        second: Double,
    ): Double {
        val difference = abs(first - second) % 360.0
        return minOf(difference, 360.0 - difference)
    }

    private const val MAX_DIFFERENCE_DEGREES = 60.0
}
