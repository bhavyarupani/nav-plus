package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import com.navplus.core.safety.SpeedCameraDao
import com.navplus.core.safety.model.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LookaheadEngine @Inject constructor(
    private val cameraDao: SpeedCameraDao,
) {
    companion object {
        private const val LOOKAHEAD_METERS = 100_000.0
        private const val CAMERA_CORRIDOR_METERS = 300.0
    }

    suspend fun eventsAhead(
        route: Route,
        currentDistanceFromStartMeters: Double,
    ): List<LookaheadEvent> = withContext(Dispatchers.Default) {
        val events = mutableListOf<LookaheadEvent>()
        val remaining = LOOKAHEAD_METERS

        // Walk route geometry, skip already-passed portion
        val geometry = route.geometry
        var accumulated = 0.0
        val aheadPoints = mutableListOf<Pair<LatLng, Double>>() // point, distFromStart
        for (i in 1 until geometry.size) {
            val seg = geometry[i - 1].distanceTo(geometry[i])
            accumulated += seg
            if (accumulated >= currentDistanceFromStartMeters) {
                aheadPoints.add(geometry[i] to accumulated - currentDistanceFromStartMeters)
            }
            if (accumulated - currentDistanceFromStartMeters > remaining) break
        }
        if (aheadPoints.isEmpty()) return@withContext emptyList()

        // Speed cameras
        val bounds = routeBounds(aheadPoints.map { it.first })
        val cameras = cameraDao.getCamerasInBoundingBox(
            bounds.minLat, bounds.maxLat, bounds.minLng, bounds.maxLng
        )
        for (cam in cameras) {
            val camLoc = LatLng(cam.lat, cam.lng)
            val (nearPoint, dist) = aheadPoints.minByOrNull { it.first.distanceTo(camLoc) } ?: continue
            if (nearPoint.distanceTo(camLoc) > CAMERA_CORRIDOR_METERS) continue
            events.add(LookaheadEvent(
                distanceMeters = dist,
                type = LookaheadEventType.SPEED_CAMERA,
                title = cam.speedLimitKph?.let { "Camera · ${it} km/h" } ?: "Speed camera",
                emoji = "📷",
                position = camLoc,
                severity = LookaheadSeverity.WARNING,
            ))
        }

        // Border crossings from route steps
        for (step in route.steps) {
            val borderTitle = detectBorderFromStep(step.streetName ?: "") ?: continue
            val stepDist = aheadPoints.minByOrNull { it.first.distanceTo(step.startLocation) }?.second ?: continue
            events.add(LookaheadEvent(
                distanceMeters = stepDist,
                type = LookaheadEventType.BORDER_CROSSING,
                title = borderTitle,
                emoji = "🛂",
                position = step.startLocation,
                severity = LookaheadSeverity.INFO,
            ))
        }

        events.sortedBy { it.distanceMeters }
    }

    private fun detectBorderFromStep(streetName: String): String? {
        // Heuristic: route steps with known border crossing keywords
        val lower = streetName.lowercase()
        return when {
            "grenze" in lower || "border" in lower || "frontière" in lower || "confine" in lower -> streetName
            else -> null
        }
    }

    private fun routeBounds(points: List<LatLng>): Bounds {
        val lats = points.map { it.lat }
        val lngs = points.map { it.lng }
        return Bounds(lats.min(), lats.max(), lngs.min(), lngs.max())
    }

    private data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)
}
