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
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class UpcomingRouteRoadFeature(
    val point: RoadInfrastructurePoint,
    val distanceMeters: Int,
    val confidence: RouteMatchConfidence = RouteMatchConfidence.MEDIUM,
) {
    fun compactText(): String {
        val distance =
            if (distanceMeters >= 1_000) {
                String.format(Locale.GERMANY, "%.1f km", distanceMeters / 1_000.0)
            } else {
                "$distanceMeters m"
            }
        return "${point.title} in $distance"
    }
}

/** Keeps mapped road features on the active approach, in route order. */
object RouteRoadFeatureAnalyzer {
    fun analyze(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
        points: List<RoadInfrastructurePoint>,
    ): List<UpcomingRouteRoadFeature> {
        val geometry = route.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (geometry.size < 2) return emptyList()
        val cumulative = cumulativeDistances(geometry)
        val currentAlong =
            current?.let { projectOntoRoute(it, geometry, cumulative).alongMeters }
                ?: 0.0
        return points
            .asSequence()
            .filter(RoadInfrastructurePoint::isRouteGuidanceFeature)
            .map { point ->
                val projection = projectOntoRoute(point.coordinate, geometry, cumulative)
                point to projection
            }.filter { (point, projection) ->
                projection.crossTrackMeters <= routeMatchDistanceMeters(point) &&
                    projection.alongMeters >= currentAlong + MIN_AHEAD_METERS &&
                    projection.alongMeters <= currentAlong + MAX_SIGN_LOOKAHEAD_METERS &&
                    RouteDirectionMatcher.evaluate(
                        projection.segmentBearingDegrees,
                        listOf(point.direction),
                    ) != DirectionMatch.MISMATCH
            }.map { (point, projection) ->
                val directionMatch =
                    RouteDirectionMatcher.evaluate(
                        projection.segmentBearingDegrees,
                        listOf(point.direction),
                    )
                UpcomingRouteRoadFeature(
                    point = point,
                    distanceMeters = (projection.alongMeters - currentAlong).toInt().coerceAtLeast(0),
                    confidence =
                        if (
                            directionMatch == DirectionMatch.MATCH || projection.crossTrackMeters <= 6.0
                        ) {
                            RouteMatchConfidence.HIGH
                        } else {
                            RouteMatchConfidence.MEDIUM
                        },
                )
            }.sortedBy(UpcomingRouteRoadFeature::distanceMeters)
            .distinctBy { it.point.id }
            .toList()
    }

    internal fun routeAhead(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
    ): List<RoadCoordinate> {
        if (route.size < 2) return emptyList()
        val cumulative = cumulativeDistances(route)
        val currentProjection = current?.let { projectOntoRoute(it, route, cumulative) }
        val currentAlong = currentProjection?.alongMeters ?: 0.0
        val result = mutableListOf<RoadCoordinate>()
        current?.let(result::add)
        route.forEachIndexed { index, coordinate ->
            val distance = cumulative[index]
            if (distance >= currentAlong && distance <= currentAlong + QUERY_LOOKAHEAD_METERS) {
                result += coordinate
            }
        }
        return result.distinct().takeIf { it.size >= 2 } ?: route.take(2)
    }

    private fun cumulativeDistances(route: List<RoadCoordinate>): List<Double> =
        MutableList(route.size) { 0.0 }.also { cumulative ->
            for (index in 1 until route.size) {
                cumulative[index] = cumulative[index - 1] +
                    distanceMeters(route[index - 1], route[index])
            }
        }

    private fun projectOntoRoute(
        point: RoadCoordinate,
        route: List<RoadCoordinate>,
        cumulative: List<Double>,
    ): RouteProjection {
        var best = RouteProjection(Double.POSITIVE_INFINITY, 0.0, 0.0)
        route.zipWithNext().forEachIndexed { index, (start, end) ->
            val latitudeScale = 111_320.0
            val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
            val ax = (start.longitude - point.longitude) * longitudeScale
            val ay = (start.latitude - point.latitude) * latitudeScale
            val bx = (end.longitude - point.longitude) * longitudeScale
            val by = (end.latitude - point.latitude) * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val fraction =
                if (lengthSquared <= .01) {
                    0.0
                } else {
                    (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                }
            val crossTrack =
                sqrt(
                    (ax + fraction * dx) * (ax + fraction * dx) +
                        (ay + fraction * dy) * (ay + fraction * dy),
                )
            if (crossTrack < best.crossTrackMeters) {
                val segmentLength = cumulative[index + 1] - cumulative[index]
                best =
                    RouteProjection(
                        crossTrackMeters = crossTrack,
                        alongMeters = cumulative[index] + segmentLength * fraction,
                        segmentBearingDegrees =
                            RouteDirectionMatcher.bearing(
                                start.latitude,
                                start.longitude,
                                end.latitude,
                                end.longitude,
                            ),
                    )
            }
        }
        return best
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

    private data class RouteProjection(
        val crossTrackMeters: Double,
        val alongMeters: Double,
        val segmentBearingDegrees: Double,
    )

    private fun routeMatchDistanceMeters(point: RoadInfrastructurePoint): Double =
        when (point.type) {
            com.roadpulse.auto.traffic.RoadInfrastructureType.SCHOOL_ZONE -> 30.0
            com.roadpulse.auto.traffic.RoadInfrastructureType.BRIDGE,
            com.roadpulse.auto.traffic.RoadInfrastructureType.TUNNEL,
            com.roadpulse.auto.traffic.RoadInfrastructureType.TOLL,
            com.roadpulse.auto.traffic.RoadInfrastructureType.ROAD_RULE_START,
            com.roadpulse.auto.traffic.RoadInfrastructureType.ROAD_RULE_END,
            com.roadpulse.auto.traffic.RoadInfrastructureType.SPEED_LIMIT_SIGN,
            com.roadpulse.auto.traffic.RoadInfrastructureType.MOTORWAY_JUNCTION,
            -> 18.0
            else -> 10.0
        }

    private const val MIN_AHEAD_METERS = 12.0
    private const val MAX_SIGN_LOOKAHEAD_METERS = 5_000.0
    private const val QUERY_LOOKAHEAD_METERS = 5_500.0
}

/** Refreshes cached OpenStreetMap data and publishes route-only upcoming road features. */
object RouteRoadFeatureGuidance {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(List<UpcomingRouteRoadFeature>) -> Unit>()
    private val refreshInProgress = AtomicBoolean(false)
    private var repository: OpenStreetMapRoadInfrastructureRepository? = null
    private var cachedPoints: List<RoadInfrastructurePoint> = emptyList()
    private var lastDataRefreshMillis = 0L

    @Volatile
    var latest: List<UpcomingRouteRoadFeature> = emptyList()
        private set

    /** Lane-topology way segments (`turn:lanes`, `destination:lanes`) from the same OSM fetch as
     * [latest], for `SignboardGuidanceEngine` to associate with a matched motorway junction. */
    @Volatile
    var latestLaneTopologySections: List<com.roadpulse.auto.traffic.LaneTopologyWaySection> = emptyList()
        private set

    fun addListener(listener: (List<UpcomingRouteRoadFeature>) -> Unit) {
        listeners += listener
        mainHandler.post { listener(latest) }
    }

    fun removeListener(listener: (List<UpcomingRouteRoadFeature>) -> Unit) {
        listeners -= listener
    }

    fun clear() {
        latest = emptyList()
        cachedPoints = emptyList()
        latestLaneTopologySections = emptyList()
        listeners.forEach { listener -> mainHandler.post { listener(emptyList()) } }
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
        publish(route, location)

        val now = System.currentTimeMillis()
        if (!force && now - lastDataRefreshMillis < DATA_REFRESH_INTERVAL_MILLIS) return
        if (!refreshInProgress.compareAndSet(false, true)) return
        lastDataRefreshMillis = now
        val ahead =
            RouteRoadFeatureAnalyzer.routeAhead(
                route,
                location?.let { RoadCoordinate(it.latitude, it.longitude) },
            )
        if (ahead.size < 2) {
            refreshInProgress.set(false)
            return
        }
        val appContext = context.applicationContext
        Thread {
            val result =
                runCatching {
                    val readyRepository =
                        repository
                            ?: OpenStreetMapRoadInfrastructureRepository(appContext).also { repository = it }
                    readyRepository.infrastructureInBounds(
                        southLatitude = ahead.minOf { it.latitude } - ROUTE_BOUNDS_PADDING_DEGREES,
                        westLongitude = ahead.minOf { it.longitude } - ROUTE_BOUNDS_PADDING_DEGREES,
                        northLatitude = ahead.maxOf { it.latitude } + ROUTE_BOUNDS_PADDING_DEGREES,
                        eastLongitude = ahead.maxOf { it.longitude } + ROUTE_BOUNDS_PADDING_DEGREES,
                    )
                }
            mainHandler.post {
                refreshInProgress.set(false)
                result.onSuccess { infrastructure ->
                    cachedPoints = infrastructure.points.filter(RoadInfrastructurePoint::isRouteGuidanceFeature)
                    latestLaneTopologySections = infrastructure.laneTopologySections
                    publish(route, lastKnownLocation(appContext))
                }
            }
        }.start()
    }

    private fun publish(
        route: List<RoadCoordinate>,
        location: Location?,
    ) {
        val upcoming =
            RouteRoadFeatureAnalyzer.analyze(
                route = route,
                current = location?.let { RoadCoordinate(it.latitude, it.longitude) },
                points = cachedPoints,
            )
        if (upcoming != latest) {
            latest = upcoming
            listeners.forEach { it(upcoming) }
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

    private const val DATA_REFRESH_INTERVAL_MILLIS = 45_000L
    private const val ROUTE_BOUNDS_PADDING_DEGREES = .003
}
