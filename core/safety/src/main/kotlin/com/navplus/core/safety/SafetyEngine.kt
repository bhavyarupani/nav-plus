package com.navplus.core.safety

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.RoadEventType
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.Severity
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

        val lookaheadMeters = when {
            speedKph > 120 -> 3_000.0
            speedKph > 80  -> 2_500.0
            else           -> 2_000.0
        }

        val routeSegmentAhead = route.geometry.filter { point ->
            val dist = position.distanceTo(point)
            dist in 0.0..lookaheadMeters
        }

        val cameras = cameraRepository.getCamerasNear(
            lat = position.lat, lng = position.lng, radiusMeters = lookaheadMeters
        )

        val relevantAlerts = cameras
            .filter { camera -> isCameraRelevant(camera, routeSegmentAhead, headingDeg) }
            .map { camera ->
                SafetyAlert(
                    id = camera.id,
                    type = camera.type.toEventType(),
                    distanceMeters = position.distanceTo(camera.position),
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
        routeAhead: List<LatLng>,
        headingDeg: Float,
    ): Boolean {
        val isNearRoute = routeAhead.any { point -> point.distanceTo(camera.position) < ROUTE_MATCH_RADIUS_M }
        if (!isNearRoute) return false

        val cameraDir = camera.directionDeg ?: return true
        val headingDiff = abs(((headingDeg - cameraDir + 540) % 360) - 180)
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
        private const val ROUTE_MATCH_RADIUS_M = 30.0
        private const val DIRECTION_TOLERANCE_DEG = 45f
    }
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
