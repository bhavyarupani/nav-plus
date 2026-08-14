package com.roadpulse.auto.driving

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.navigation.Navigator
import com.roadpulse.auto.traffic.OpenStreetMapRoadInfrastructureRepository
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.SpeedLimitRoadSection
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class SpeedLimitAheadSummary(
    val currentLimitKph: Int?,
    val currentUnlimited: Boolean,
    val nextLimitKph: Int?,
    val nextUnlimited: Boolean,
    val distanceMeters: Int,
    val secondsAtCurrentSpeed: Int?,
    val currentSpeedKph: Int?,
) {
    fun compactText(): String {
        val current = limitText(currentLimitKph, currentUnlimited)
        val distance =
            if (distanceMeters >= 1_000) {
                String.format(Locale.GERMANY, "%.1f km", distanceMeters / 1_000.0)
            } else {
                "$distanceMeters m"
            }
        val time =
            secondsAtCurrentSpeed?.let { seconds ->
                val minutes = (seconds + 59) / 60
                "$minutes min at ${currentSpeedKph ?: 0} km/h"
            } ?: "time available once moving"
        val change =
            if (nextLimitKph != null || nextUnlimited) {
                "$current → ${limitText(nextLimitKph, nextUnlimited)} in $distance"
            } else {
                "$current · no mapped change for $distance"
            }
        return "$change · $time"
    }

    fun phoneText(): String =
        "NEXT SPEED LIMIT · ${compactText()}\n" +
            "Mapped-road estimate; temporary and conditional limits may differ"

    private fun limitText(
        limit: Int?,
        unlimited: Boolean,
    ): String =
        when {
            unlimited -> "unrestricted"
            limit != null -> "$limit km/h"
            else -> "unknown"
        }
}

object SpeedLimitRouteAnalyzer {
    fun analyze(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
        currentSpeedKph: Double?,
        sections: List<SpeedLimitRoadSection>,
    ): SpeedLimitAheadSummary? {
        if (route.size < 2 || sections.isEmpty()) return null
        val samples = sampleRouteAhead(route, current)
        if (samples.size < 3) return null
        val matches = samples.map { coordinate -> nearestSection(coordinate, sections) }
        val currentIndex =
            matches.indexOfFirst { it != null }.takeIf { it in 0..MAX_CURRENT_LOOKAHEAD_SAMPLES }
                ?: return null
        val currentSection = matches[currentIndex] ?: return null
        val currentKey = currentSection.limitKey()
        var changeIndex: Int? = null
        var nextSection: SpeedLimitRoadSection? = null
        for (index in currentIndex + 1 until matches.size - 1) {
            val candidate = matches[index] ?: continue
            val confirmation = matches[index + 1] ?: continue
            if (candidate.limitKey() != currentKey && confirmation.limitKey() == candidate.limitKey()) {
                changeIndex = index
                nextSection = candidate
                break
            }
        }
        val targetIndex = changeIndex ?: matches.lastIndex
        val distance = ((targetIndex - currentIndex) * SAMPLE_SPACING_METERS).roundToInt()
        if (distance < MIN_CHANGE_DISTANCE_METERS) return null
        val speed = currentSpeedKph?.takeIf { it >= MIN_MOVING_SPEED_KPH }
        val seconds = speed?.let { (distance / (it / 3.6)).roundToInt().coerceAtLeast(1) }
        return SpeedLimitAheadSummary(
            currentLimitKph = currentSection.speedLimitKph,
            currentUnlimited = currentSection.unlimited,
            nextLimitKph = nextSection?.speedLimitKph,
            nextUnlimited = nextSection?.unlimited == true,
            distanceMeters = distance,
            secondsAtCurrentSpeed = seconds,
            currentSpeedKph = speed?.roundToInt(),
        )
    }

    internal fun sampleRouteAhead(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
    ): List<RoadCoordinate> {
        val valid = route.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (valid.size < 2) return emptyList()
        val startIndex =
            current?.let { location ->
                valid.indices.minByOrNull { distanceMeters(location, valid[it]) }
            } ?: 0
        val forward = valid.drop(startIndex ?: 0)
        if (forward.size < 2) return emptyList()
        val cumulative = MutableList(forward.size) { 0.0 }
        for (index in 1 until forward.size) {
            cumulative[index] = cumulative[index - 1] + distanceMeters(forward[index - 1], forward[index])
        }
        val available = minOf(MAX_LOOKAHEAD_METERS, cumulative.last())
        if (available < SAMPLE_SPACING_METERS * 2) return emptyList()
        return generateSequence(0.0) { it + SAMPLE_SPACING_METERS }
            .takeWhile { it <= available }
            .map { interpolate(forward, cumulative, it) }
            .toList()
    }

    private fun nearestSection(
        coordinate: RoadCoordinate,
        sections: List<SpeedLimitRoadSection>,
    ): SpeedLimitRoadSection? =
        sections
            .asSequence()
            .filter { it.speedLimitKph != null || it.unlimited }
            .map { section -> section to distanceToGeometry(coordinate, section.geometry) }
            .filter { (_, distance) -> distance <= ROAD_MATCH_DISTANCE_METERS }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    private fun distanceToGeometry(
        point: RoadCoordinate,
        geometry: List<RoadCoordinate>,
    ): Double {
        if (geometry.size < 2) return Double.POSITIVE_INFINITY
        return geometry.zipWithNext().minOf { (start, end) ->
            distanceToSegment(point, start, end)
        }
    }

    private fun distanceToSegment(
        point: RoadCoordinate,
        start: RoadCoordinate,
        end: RoadCoordinate,
    ): Double {
        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
        val ax = (start.longitude - point.longitude) * longitudeScale
        val ay = (start.latitude - point.latitude) * latitudeScale
        val bx = (end.longitude - point.longitude) * longitudeScale
        val by = (end.latitude - point.latitude) * latitudeScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.01) return sqrt(ax * ax + ay * ay)
        val projection = (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val x = ax + projection * dx
        val y = ay + projection * dy
        return sqrt(x * x + y * y)
    }

    private fun interpolate(
        route: List<RoadCoordinate>,
        cumulative: List<Double>,
        target: Double,
    ): RoadCoordinate {
        if (target <= 0.0) return route.first()
        val endIndex =
            cumulative.indexOfFirst { it >= target }.takeIf { it >= 1 }
                ?: return route.last()
        val startIndex = endIndex - 1
        val segment = cumulative[endIndex] - cumulative[startIndex]
        if (segment <= 0.0) return route[endIndex]
        val fraction = (target - cumulative[startIndex]) / segment
        return RoadCoordinate(
            route[startIndex].latitude + (route[endIndex].latitude - route[startIndex].latitude) * fraction,
            route[startIndex].longitude + (route[endIndex].longitude - route[startIndex].longitude) * fraction,
        )
    }

    private fun distanceMeters(
        start: RoadCoordinate,
        end: RoadCoordinate,
    ): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val h =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun SpeedLimitRoadSection.limitKey(): String = if (unlimited) "unlimited" else "speed:$speedLimitKph"

    private const val SAMPLE_SPACING_METERS = 50.0
    private const val MAX_LOOKAHEAD_METERS = 15_000.0
    private const val ROAD_MATCH_DISTANCE_METERS = 45.0
    private const val MAX_CURRENT_LOOKAHEAD_SAMPLES = 6
    private const val MIN_CHANGE_DISTANCE_METERS = 100
    private const val MIN_MOVING_SPEED_KPH = 3.0
}

/** Loads mapped speed-limit road geometry and keeps a live route-ahead estimate. */
object SpeedLimitAheadGuidance {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(SpeedLimitAheadSummary?) -> Unit>()
    private val refreshInProgress = AtomicBoolean(false)
    private var repository: OpenStreetMapRoadInfrastructureRepository? = null
    private var cachedSections: List<SpeedLimitRoadSection> = emptyList()
    private var lastDataRefreshMillis = 0L

    @Volatile
    var latest: SpeedLimitAheadSummary? = null
        private set

    fun addListener(listener: (SpeedLimitAheadSummary?) -> Unit) {
        listeners += listener
        mainHandler.post { listener(latest) }
    }

    fun removeListener(listener: (SpeedLimitAheadSummary?) -> Unit) {
        listeners -= listener
    }

    fun clear() {
        latest = null
        cachedSections = emptyList()
        listeners.forEach { listener -> mainHandler.post { listener(null) } }
    }

    fun refresh(
        context: Context,
        navigator: Navigator,
        force: Boolean = false,
    ) {
        val route =
            runCatching {
                navigator.currentRouteSegment?.latLngs.orEmpty().map {
                    RoadCoordinate(it.latitude, it.longitude)
                }
            }.getOrDefault(emptyList())
        if (route.size < 2) return
        val location = lastKnownLocation(context)
        publishEstimate(route, location)

        val now = System.currentTimeMillis()
        if (!force && now - lastDataRefreshMillis < DATA_REFRESH_INTERVAL_MILLIS) return
        if (!refreshInProgress.compareAndSet(false, true)) return
        lastDataRefreshMillis = now
        val ahead =
            SpeedLimitRouteAnalyzer.sampleRouteAhead(
                route,
                location?.let { RoadCoordinate(it.latitude, it.longitude) },
            )
        if (ahead.size < 2) {
            refreshInProgress.set(false)
            return
        }
        val padding = ROUTE_BOUNDS_PADDING_DEGREES
        val appContext = context.applicationContext
        Thread {
            val result =
                runCatching {
                    val readyRepository =
                        repository ?: OpenStreetMapRoadInfrastructureRepository(appContext).also {
                            repository = it
                        }
                    readyRepository
                        .infrastructureInBounds(
                            southLatitude = ahead.minOf { it.latitude } - padding,
                            westLongitude = ahead.minOf { it.longitude } - padding,
                            northLatitude = ahead.maxOf { it.latitude } + padding,
                            eastLongitude = ahead.maxOf { it.longitude } + padding,
                        ).speedLimitSections
                }
            mainHandler.post {
                refreshInProgress.set(false)
                result.onSuccess { sections ->
                    cachedSections = sections
                    publishEstimate(route, lastKnownLocation(appContext))
                }
            }
        }.start()
    }

    private fun publishEstimate(
        route: List<RoadCoordinate>,
        location: Location?,
    ) {
        if (cachedSections.isEmpty()) return
        val summary =
            SpeedLimitRouteAnalyzer.analyze(
                route = route,
                current = location?.let { RoadCoordinate(it.latitude, it.longitude) },
                currentSpeedKph = location?.takeIf(Location::hasSpeed)?.speed?.times(3.6),
                sections = cachedSections,
            )
        if (summary != latest) {
            latest = summary
            listeners.forEach { it(summary) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java)
        return sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
    }

    private const val DATA_REFRESH_INTERVAL_MILLIS = 2 * 60_000L
    private const val ROUTE_BOUNDS_PADDING_DEGREES = .004
}
