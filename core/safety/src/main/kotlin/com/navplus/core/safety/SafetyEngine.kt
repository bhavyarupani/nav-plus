package com.navplus.core.safety

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.RoadEventType
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.Severity
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SafetyAlert
import com.navplus.core.safety.model.SpeedCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SafetyEngine @Inject constructor(
    private val cameraRepository: SpeedCameraRepository,
) {
    private val _alerts = MutableStateFlow<List<SafetyAlert>>(emptyList())
    val alerts: StateFlow<List<SafetyAlert>> = _alerts.asStateFlow()

    private var activeRoute: Route? = null

    fun setRoute(route: Route) {
        activeRoute = route
    }

    fun clearRoute() {
        activeRoute = null
        _alerts.value = emptyList()
    }

    suspend fun updatePosition(position: LatLng, headingDeg: Float, speedKph: Float) {
        val route = activeRoute ?: return

        val lookaheadMeters = CAMERA_LOOKAHEAD_METERS
        val routeProgressMeters = route.distanceAlongRoute(position)

        val cameras = cameraRepository.getCamerasNear(
            lat = position.lat, lng = position.lng, radiusMeters = lookaheadMeters
        )

        val relevantAlerts = cameras
            .mapNotNull { camera ->
                val match = camera.routeMatch(route, routeProgressMeters) ?: return@mapNotNull null
                if (!isCameraRelevant(camera, match, headingDeg)) return@mapNotNull null
                SafetyAlert(
                    id = camera.id,
                    type = camera.type.toEventType(),
                    distanceMeters = match.distanceAheadMeters,
                    severity = alertSeverity(camera, speedKph),
                    title = camera.type.displayName(),
                    speedLimitKph = camera.speedLimitKph,
                    camera = camera,
                )
            }
            .sortedBy { it.distanceMeters }

        _alerts.value = relevantAlerts
    }

    private fun isCameraRelevant(
        camera: SpeedCamera,
        match: RouteCameraMatch,
        headingDeg: Float,
    ): Boolean {
        if (match.distanceFromRouteMeters > ROUTE_MATCH_RADIUS_M) return false
        if (match.distanceAheadMeters !in 0.0..CAMERA_LOOKAHEAD_METERS) return false

        val cameraDir = camera.directionDeg ?: return true
        val routeHeading = match.routeBearingDeg.takeIf { !it.isNaN() } ?: headingDeg
        val headingDiff = abs(((routeHeading - cameraDir + 540) % 360) - 180)
        return headingDiff < DIRECTION_TOLERANCE_DEG
    }

    private fun alertSeverity(camera: SpeedCamera, speedKph: Float): Severity {
        val limit = camera.speedLimitKph ?: return Severity.MEDIUM
        return when {
            speedKph > limit + 20 -> Severity.CRITICAL
            speedKph > limit + 10 -> Severity.HIGH
            speedKph > limit      -> Severity.MEDIUM
            else                  -> Severity.INFO
        }
    }

    companion object {
        private const val CAMERA_LOOKAHEAD_METERS = 3_000.0
        private const val ROUTE_MATCH_RADIUS_M = 45.0
        private const val DIRECTION_TOLERANCE_DEG = 45f
    }
}

private data class RouteCameraMatch(
    val distanceAheadMeters: Double,
    val distanceFromRouteMeters: Double,
    val routeBearingDeg: Float,
)

private fun SpeedCamera.routeMatch(route: Route, routeProgressMeters: Double): RouteCameraMatch? {
    if (route.geometry.size < 2) return null
    var accumulated = 0.0
    var best: RouteCameraMatch? = null
    val cameraPosition = position

    for (i in 0 until route.geometry.lastIndex) {
        val start = route.geometry[i]
        val end = route.geometry[i + 1]
        val segmentMeters = start.distanceTo(end)
        if (segmentMeters <= 0.0) continue

        val projected = cameraPosition.projectOntoSegment(start, end)
        val distanceFromRoute = cameraPosition.distanceTo(projected)
        val distanceOnSegment = start.distanceTo(projected).coerceIn(0.0, segmentMeters)
        val distanceFromStart = accumulated + distanceOnSegment
        val distanceAhead = distanceFromStart - routeProgressMeters
        val candidate = RouteCameraMatch(
            distanceAheadMeters = distanceAhead,
            distanceFromRouteMeters = distanceFromRoute,
            routeBearingDeg = start.bearingTo(end).toFloat(),
        )
        if (best == null || distanceFromRoute < best.distanceFromRouteMeters) {
            best = candidate
        }
        accumulated += segmentMeters
    }

    return best
}

private fun Route.distanceAlongRoute(position: LatLng): Double {
    if (geometry.size < 2) return 0.0
    var accumulated = 0.0
    var bestDistanceFromStart = 0.0
    var bestDistanceFromRoute = Double.MAX_VALUE

    for (i in 0 until geometry.lastIndex) {
        val start = geometry[i]
        val end = geometry[i + 1]
        val segmentMeters = start.distanceTo(end)
        if (segmentMeters <= 0.0) continue

        val projected = position.projectOntoSegment(start, end)
        val distanceFromRoute = position.distanceTo(projected)
        if (distanceFromRoute < bestDistanceFromRoute) {
            bestDistanceFromRoute = distanceFromRoute
            bestDistanceFromStart = accumulated + start.distanceTo(projected).coerceIn(0.0, segmentMeters)
        }
        accumulated += segmentMeters
    }

    return bestDistanceFromStart
}

private fun LatLng.projectOntoSegment(start: LatLng, end: LatLng): LatLng {
    val ax = end.lng - start.lng
    val ay = end.lat - start.lat
    val denom = ax * ax + ay * ay
    if (denom == 0.0) return start
    val t = (((lng - start.lng) * ax + (lat - start.lat) * ay) / denom).coerceIn(0.0, 1.0)
    return LatLng(
        lat = start.lat + t * ay,
        lng = start.lng + t * ax,
    )
}

private fun CameraType.toEventType() = when (this) {
    CameraType.FIXED_SPEED,
    CameraType.MOBILE_ZONE       -> RoadEventType.SPEED_CAMERA
    CameraType.RED_LIGHT         -> RoadEventType.RED_LIGHT_CAMERA
    CameraType.COMBINED          -> RoadEventType.SPEED_CAMERA
    CameraType.AVERAGE_SPEED_START -> RoadEventType.AVERAGE_SPEED_ZONE_START
    CameraType.AVERAGE_SPEED_END -> RoadEventType.AVERAGE_SPEED_ZONE_END
    CameraType.SECTION_CONTROL   -> RoadEventType.AVERAGE_SPEED_ZONE_START
}

private fun CameraType.displayName() = when (this) {
    CameraType.FIXED_SPEED         -> "Speed Camera"
    CameraType.RED_LIGHT           -> "Red Light Camera"
    CameraType.COMBINED            -> "Speed & Red Light Camera"
    CameraType.AVERAGE_SPEED_START -> "Average Speed Zone Start"
    CameraType.AVERAGE_SPEED_END   -> "Average Speed Zone End"
    CameraType.MOBILE_ZONE         -> "Mobile Camera Zone"
    CameraType.SECTION_CONTROL     -> "Section Control"
}
