package com.roadpulse.auto.driving

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.navigation.Navigator
import com.roadpulse.auto.alerts.AlertPolicy
import com.roadpulse.auto.alerts.AlertVisibilityMode
import com.roadpulse.auto.alerts.NearbyOpenGatsoPoi
import com.roadpulse.auto.alerts.OfficialCameraRepository
import com.roadpulse.auto.alerts.OpenGatsoDataUpdater
import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.alerts.OpenGatsoRepository
import com.roadpulse.auto.alerts.OpenStreetMapCameraRepository
import com.roadpulse.auto.alerts.mergeCameraSources
import com.roadpulse.auto.traffic.RoadCoordinate
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class UpcomingRouteCamera(
    val id: String,
    val camera: NearbyOpenGatsoPoi,
    val distanceMeters: Int,
    val confidence: RouteMatchConfidence = RouteMatchConfidence.MEDIUM,
) {
    fun compactText(): String {
        val type =
            when (camera.poi.type) {
                OpenGatsoPoiType.SPEED_CAMERA -> "speed camera"
                OpenGatsoPoiType.AVERAGE_SPEED_CAMERA -> "average-speed camera"
                OpenGatsoPoiType.RED_LIGHT_CAMERA -> "red-light camera"
                OpenGatsoPoiType.RAIL_CROSSING_CAMERA -> "rail-crossing camera"
                else -> "camera"
            }
        val speed =
            camera.poi.speedLimitKph
                ?.let { " · $it km/h" }
                .orEmpty()
        val distance =
            if (distanceMeters >= 1_000) {
                String.format(Locale.GERMANY, "%.1f km", distanceMeters / 1_000.0)
            } else {
                "$distanceMeters m"
            }
        return "Next $type$speed · $distance"
    }
}

data class RouteCameraSnapshot(
    val cameras: List<UpcomingRouteCamera> = emptyList(),
    val countryCode: String? = null,
    val blockedReason: String? = null,
    /**
     * Distance to the nearest matched camera on the active route, independent of [blockedReason]
     * - unlike [cameras], this is populated even where the camera-marker/panel feature is
     * switched off (e.g. while driving in Germany). It powers only the generic "Check speed"
     * speed-compliance nudge (SpeedComplianceAdvisor), which never surfaces a camera's location,
     * type, or countdown - see PRIVACY.md, "Speed compliance" for why that distinction matters
     * under Germany's StVO Section 23 enforcement-warning-device restriction.
     */
    val nearestCameraDistanceMeters: Int? = null,
) {
    fun phoneText(): String =
        when {
            blockedReason != null -> blockedReason
            cameras.isNotEmpty() -> "CAMERA AHEAD · ${cameras.first().compactText()}"
            else -> "No mapped enforcement camera on the current route ahead"
        }

    fun carText(): String? = blockedReason ?: cameras.firstOrNull()?.compactText()
}

object RouteCameraAnalyzer {
    fun analyze(
        route: List<RoadCoordinate>,
        current: RoadCoordinate?,
        cameras: List<NearbyOpenGatsoPoi>,
    ): List<UpcomingRouteCamera> {
        val geometry = route.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (geometry.size < 2) return emptyList()
        val cumulative = cumulativeDistances(geometry)
        val currentAlong =
            current?.let { projectOntoRoute(it, geometry, cumulative).alongMeters }
                ?: 0.0
        return cameras
            .asSequence()
            .filter { it.poi.isEnforcementLocation }
            .map { camera ->
                camera to
                    projectOntoRoute(
                        RoadCoordinate(camera.poi.latitude, camera.poi.longitude),
                        geometry,
                        cumulative,
                    )
            }.filter { (_, projection) ->
                projection.crossTrackMeters <= CAMERA_ROUTE_MATCH_METERS &&
                    projection.alongMeters >= currentAlong + MIN_AHEAD_METERS &&
                    projection.alongMeters <= currentAlong + MAX_LOOKAHEAD_METERS
            }.filter { (camera, projection) ->
                RouteDirectionMatcher.evaluate(
                    projection.segmentBearingDegrees,
                    camera.sourceRecords.map { it.direction },
                ) != DirectionMatch.MISMATCH
            }.map { (camera, projection) ->
                val directionMatch =
                    RouteDirectionMatcher.evaluate(
                        projection.segmentBearingDegrees,
                        camera.sourceRecords.map { it.direction },
                    )
                UpcomingRouteCamera(
                    id = camera.stableId(),
                    camera = camera,
                    distanceMeters = (projection.alongMeters - currentAlong).toInt().coerceAtLeast(0),
                    confidence =
                        if (
                            directionMatch == DirectionMatch.MATCH ||
                            camera.sources.size > 1 ||
                            projection.crossTrackMeters <= 10.0
                        ) {
                            RouteMatchConfidence.HIGH
                        } else {
                            RouteMatchConfidence.MEDIUM
                        },
                )
            }.sortedBy(UpcomingRouteCamera::distanceMeters)
            .distinctBy(UpcomingRouteCamera::id)
            .toList()
    }

    private fun NearbyOpenGatsoPoi.stableId(): String =
        sourceRecords
            .firstNotNullOfOrNull { record -> record.sourceId?.let { "${record.source.name}:$it" } }
            ?: listOf(
                poi.type.name,
                "%.6f".format(Locale.US, poi.latitude),
                "%.6f".format(Locale.US, poi.longitude),
            ).joinToString(":")

    internal fun cumulativeDistances(route: List<RoadCoordinate>): List<Double> =
        MutableList(route.size) { 0.0 }.also { cumulative ->
            for (index in 1 until route.size) {
                cumulative[index] = cumulative[index - 1] + distanceMeters(route[index - 1], route[index])
            }
        }

    internal fun projectOntoRoute(
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
            val x = ax + fraction * dx
            val y = ay + fraction * dy
            val crossTrack = sqrt(x * x + y * y)
            if (crossTrack < best.crossTrackMeters) {
                val segmentLength = cumulative[index + 1] - cumulative[index]
                best =
                    RouteProjection(
                        crossTrack,
                        cumulative[index] + segmentLength * fraction,
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

    internal fun distanceMeters(
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

    internal data class RouteProjection(
        val crossTrackMeters: Double,
        val alongMeters: Double,
        val segmentBearingDegrees: Double,
    )

    private const val CAMERA_ROUTE_MATCH_METERS = 30.0
    private const val MIN_AHEAD_METERS = 15.0
    private const val MAX_LOOKAHEAD_METERS = 5_000.0
}

/** Loads and merges camera sources only where active-driving display is allowed. */
object RouteCameraGuidance {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RouteCameraSnapshot) -> Unit>()
    private val refreshInProgress = AtomicBoolean(false)
    private var lastRefreshMillis = 0L
    private var lastCountryCode: String? = null
    private var cachedCameras: List<NearbyOpenGatsoPoi> = emptyList()

    @Volatile
    var latest: RouteCameraSnapshot = RouteCameraSnapshot()
        private set

    fun addListener(listener: (RouteCameraSnapshot) -> Unit) {
        listeners += listener
        mainHandler.post { listener(latest) }
    }

    fun removeListener(listener: (RouteCameraSnapshot) -> Unit) {
        listeners -= listener
    }

    fun clear() {
        cachedCameras = emptyList()
        lastCountryCode = null
        publish(RouteCameraSnapshot())
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
        val immediateCountry =
            if (
                isInsideGermanySafetyBounds(
                    location?.latitude ?: route.first().latitude,
                    location?.longitude ?: route.first().longitude,
                )
            ) {
                "DE"
            } else {
                lastCountryCode
            }
        publishForRoute(
            route,
            location,
            immediateCountry,
            allowed = !immediateCountry.isNullOrBlank() && !immediateCountry.equals("DE", ignoreCase = true),
        )

        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMillis < REFRESH_INTERVAL_MILLIS) return
        if (!refreshInProgress.compareAndSet(false, true)) return
        lastRefreshMillis = now
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
            val countryCode =
                resolveCountryCode(
                    appContext,
                    location?.latitude ?: ahead.first().latitude,
                    location?.longitude ?: ahead.first().longitude,
                )
            val policyProbe =
                com.roadpulse.auto.alerts.OpenGatsoPoi(
                    longitude = ahead.first().longitude,
                    latitude = ahead.first().latitude,
                    type = OpenGatsoPoiType.SPEED_CAMERA,
                    speedLimitKph = null,
                    description = "policy probe",
                )
            val allowed =
                AlertPolicy.mayShowOpenGatsoPoi(
                    policyProbe,
                    countryCode.orEmpty(),
                    AlertVisibilityMode.ACTIVE_DRIVING,
                )
            // Cameras are always fetched, regardless of the country policy gate: the generic
            // "Check speed" nudge (SpeedComplianceAdvisor) intentionally runs everywhere and
            // never surfaces a camera's location/type, so it isn't subject to the same
            // enforcement-warning-device restriction the exposed camera list/panel is. See the
            // nearestCameraDistanceMeters doc on RouteCameraSnapshot.
            val result = runCatching { loadCameras(appContext, ahead, location) }
            mainHandler.post {
                refreshInProgress.set(false)
                lastCountryCode = countryCode
                result.onSuccess { cameras ->
                    cachedCameras = cameras
                    publishForRoute(route, lastKnownLocation(appContext), countryCode, allowed)
                }
            }
        }.start()
    }

    private fun loadCameras(
        context: Context,
        ahead: List<RoadCoordinate>,
        location: Location?,
    ): List<NearbyOpenGatsoPoi> {
        val padding = ROUTE_BOUNDS_PADDING_DEGREES
        val south = ahead.minOf { it.latitude } - padding
        val west = ahead.minOf { it.longitude } - padding
        val north = ahead.maxOf { it.latitude } + padding
        val east = ahead.maxOf { it.longitude } + padding
        val referenceLatitude = location?.latitude ?: ahead.first().latitude
        val referenceLongitude = location?.longitude ?: ahead.first().longitude
        val openGatso =
            OpenGatsoRepository(OpenGatsoDataUpdater(context).currentDataFile())
                .enforcementLocationsInBounds(
                    south,
                    west,
                    north,
                    east,
                    referenceLatitude,
                    referenceLongitude,
                )
        val official =
            OfficialCameraRepository(context).enforcementLocationsInBounds(
                south,
                west,
                north,
                east,
                referenceLatitude,
                referenceLongitude,
            )
        val osm =
            runCatching {
                OpenStreetMapCameraRepository(context).enforcementLocationsInBounds(
                    south,
                    west,
                    north,
                    east,
                    referenceLatitude,
                    referenceLongitude,
                )
            }.getOrDefault(emptyList())
        return mergeCameraSources(mergeCameraSources(openGatso, official), osm)
    }

    private fun publishForRoute(
        route: List<RoadCoordinate>,
        location: Location?,
        countryCode: String?,
        allowed: Boolean,
    ) {
        val matched =
            RouteCameraAnalyzer.analyze(
                route,
                location?.let { RoadCoordinate(it.latitude, it.longitude) },
                cachedCameras,
            )
        val nearestDistance =
            matched
                .filter { it.distanceMeters <= SpeedComplianceAdvisor.CHECK_SPEED_CAMERA_RADIUS_METERS }
                .minOfOrNull(UpcomingRouteCamera::distanceMeters)

        if (countryCode.isNullOrBlank()) {
            publish(
                RouteCameraSnapshot(
                    blockedReason = "Camera guidance off until the driving country is confirmed",
                    nearestCameraDistanceMeters = nearestDistance,
                ),
            )
            return
        }
        if (!allowed) {
            val reason =
                if (countryCode.equals("DE", ignoreCase = true)) {
                    "Camera guidance off while driving in Germany"
                } else {
                    "Camera guidance off until the driving country is confirmed"
                }
            publish(
                RouteCameraSnapshot(
                    countryCode = countryCode,
                    blockedReason = reason,
                    nearestCameraDistanceMeters = nearestDistance,
                ),
            )
            return
        }
        publish(
            RouteCameraSnapshot(
                cameras = matched,
                countryCode = countryCode,
                nearestCameraDistanceMeters = nearestDistance,
            ),
        )
    }

    private fun publish(snapshot: RouteCameraSnapshot) {
        if (snapshot == latest) return
        latest = snapshot
        listeners.forEach { it(snapshot) }
    }

    private fun resolveCountryCode(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): String? {
        if (isInsideGermanySafetyBounds(latitude, longitude)) return "DE"
        return resolveCountryCodeFromGeocoder(context, latitude, longitude)
            ?: resolveCountryCodeFromNominatim(latitude, longitude)
    }

    @Suppress("DEPRECATION")
    private fun resolveCountryCodeFromGeocoder(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): String? =
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            Geocoder(context, Locale.ROOT)
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.countryCode
                ?.uppercase(Locale.ROOT)
        }.getOrNull()

    // Free fallback for devices where Android's Geocoder is absent or fails (it needs a
    // Google-backed or OEM geocoding service that isn't guaranteed to exist on every device).
    private fun resolveCountryCodeFromNominatim(
        latitude: Double,
        longitude: Double,
    ): String? =
        runCatching {
            val url =
                String.format(
                    Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=3",
                    latitude,
                    longitude,
                )
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "RoadPulse/0.1 personal Android navigation prototype")
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body)
                    .optJSONObject("address")
                    ?.optString("country_code")
                    ?.takeIf(String::isNotBlank)
                    ?.uppercase(Locale.ROOT)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    private fun isInsideGermanySafetyBounds(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in 47.1..55.2 && longitude in 5.5..15.6

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

    private const val REFRESH_INTERVAL_MILLIS = 45_000L
    private const val ROUTE_BOUNDS_PADDING_DEGREES = .005
}
