package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.distanceTo
import com.navplus.core.navigation.traffic.SignalSourceType
import com.navplus.core.navigation.traffic.SignalState
import com.navplus.core.navigation.traffic.TrafficSignalEngine
import com.navplus.core.navigation.traffic.TrafficSignalRoadEvent
import com.navplus.core.safety.model.SpeedCamera
import com.navplus.core.safety.SpeedCameraDao
import com.navplus.core.safety.model.CameraType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LookaheadEngine @Inject constructor(
    private val cameraDao: SpeedCameraDao,
    private val trafficSignalEngine: TrafficSignalEngine? = null,
) {
    companion object {
        const val LOOKAHEAD_METERS = 3_000.0
        private const val CAMERA_CORRIDOR_METERS = 45.0
    }

    suspend fun eventsAhead(
        route: Route,
        currentDistanceFromStartMeters: Double,
        currentSpeedKph: Float? = null,
    ): List<LookaheadEvent> = withContext(Dispatchers.Default) {
        val events = mutableListOf<LookaheadEvent>()

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
            if (accumulated - currentDistanceFromStartMeters > LOOKAHEAD_METERS) break
        }
        if (aheadPoints.isEmpty()) return@withContext emptyList()

        events.addAll(
            trafficSignalEngine?.roadEventsAhead(
                route = route,
                currentDistanceFromStartMeters = currentDistanceFromStartMeters,
                currentSpeedKph = currentSpeedKph,
            ).orEmpty().map { it.toLookaheadEvent() }
        )

        // Speed cameras
        val bounds = routeBounds(route.geometry, paddingMeters = CAMERA_CORRIDOR_METERS)
        val cameras = cameraDao.getCamerasInBoundingBox(
            bounds.minLat, bounds.maxLat, bounds.minLng, bounds.maxLng
        )
        for (cam in cameras) {
            val camLoc = LatLng(cam.lat, cam.lng)
            val match = cam.routeMatch(route, currentDistanceFromStartMeters) ?: continue
            if (match.distanceFromRouteMeters > CAMERA_CORRIDOR_METERS) continue
            if (match.distanceAheadMeters !in 0.0..LOOKAHEAD_METERS) continue
            events.add(LookaheadEvent(
                distanceMeters = match.distanceAheadMeters,
                type = LookaheadEventType.SPEED_CAMERA,
                title = cam.lookaheadTitle(),
                emoji = cam.lookaheadEmoji(),
                position = camLoc,
                severity = LookaheadSeverity.WARNING,
            ))
        }

        val stepPositions = route.stepDistancesFromStart()
        var previousSpeedLimit: Int? = null
        for ((step, stepDistanceFromStart) in stepPositions) {
            val distanceAhead = stepDistanceFromStart - currentDistanceFromStartMeters
            if (distanceAhead !in 0.0..LOOKAHEAD_METERS) {
                previousSpeedLimit = step.speedLimitKph ?: previousSpeedLimit
                continue
            }

            step.primaryEvent(distanceAhead)?.let(events::add)
            events.addAll(step.zoneEvents(distanceAhead))
            step.laneEvent(distanceAhead)?.let(events::add)

            val limit = step.speedLimitKph
            if (limit != null && limit != previousSpeedLimit) {
                events.add(
                    LookaheadEvent(
                        distanceMeters = distanceAhead,
                        type = LookaheadEventType.SPEED_LIMIT,
                        title = "Speed limit $limit",
                        emoji = "🚦",
                        position = step.startLocation,
                        severity = if (limit <= 30) LookaheadSeverity.WARNING else LookaheadSeverity.INFO,
                    )
                )
            }
            previousSpeedLimit = limit ?: previousSpeedLimit
        }

        events
            .distinctBy { "${it.type}|${it.title}|${it.distanceMeters.toInt()}" }
            .sortedBy { it.distanceMeters }
    }

    private fun TrafficSignalRoadEvent.toLookaheadEvent(): LookaheadEvent {
        val title = when {
            glosaAdvice != null -> "Green wave ${glosaAdvice.recommendedSpeedMinKph}-${glosaAdvice.recommendedSpeedMaxKph} km/h"
            sourceType == SignalSourceType.STATIC -> "Traffic signal"
            state == SignalState.UNKNOWN -> "Traffic signal"
            sourceType == SignalSourceType.PREDICTED -> "${state.label()} likely"
            else -> state.label()
        }
        val subtitle = when {
            glosaAdvice != null -> "Likely GREEN on arrival"
            predictedChangeEpochMs != null && state != SignalState.UNKNOWN -> "Estimated change ${estimatedSeconds(predictedChangeEpochMs)}"
            sourceType == SignalSourceType.STATIC -> null
            state == SignalState.UNKNOWN -> "Live state unavailable"
            else -> "${sourceType.name.lowercase()} · ${(confidence * 100).toInt()}%"
        }
        return LookaheadEvent(
            distanceMeters = distanceMeters,
            type = LookaheadEventType.TRAFFIC_SIGNAL,
            title = title,
            subtitle = subtitle,
            emoji = "🚦",
            position = com.navplus.core.common.model.LatLng(latitude, longitude),
            severity = if (state == SignalState.RED) LookaheadSeverity.WARNING else LookaheadSeverity.INFO,
            trafficSignal = this,
        )
    }

    private fun SpeedCamera.lookaheadTitle(): String = when (type) {
        CameraType.RED_LIGHT -> "Red light camera"
        CameraType.COMBINED -> speedLimitKph?.let { "Combined camera · ${it} km/h" } ?: "Combined camera"
        CameraType.AVERAGE_SPEED_START -> "Average speed start"
        CameraType.AVERAGE_SPEED_END -> "Average speed end"
        CameraType.SECTION_CONTROL -> "Section control"
        CameraType.MOBILE_ZONE -> speedLimitKph?.let { "Camera zone · ${it} km/h" } ?: "Camera zone ahead"
        CameraType.FIXED_SPEED -> speedLimitKph?.let { "Camera · ${it} km/h" } ?: "Camera ahead"
    }

    private fun SpeedCamera.lookaheadEmoji(): String = when (type) {
        CameraType.RED_LIGHT, CameraType.COMBINED -> "🚦"
        CameraType.AVERAGE_SPEED_START, CameraType.AVERAGE_SPEED_END, CameraType.SECTION_CONTROL -> "↔"
        CameraType.MOBILE_ZONE -> "!"
        CameraType.FIXED_SPEED -> "📷"
    }

    private fun SignalState.label(): String = when (this) {
        SignalState.RED -> "RED"
        SignalState.RED_YELLOW -> "RED-YELLOW"
        SignalState.YELLOW -> "YELLOW"
        SignalState.GREEN -> "GREEN"
        SignalState.FLASHING -> "FLASHING"
        SignalState.OFF -> "Signal off"
        SignalState.UNKNOWN -> "Traffic signal"
    }

    private fun estimatedSeconds(epochMs: Long): String {
        val seconds = ((epochMs - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
        return "~${seconds} sec"
    }

    private fun routeBounds(points: List<LatLng>, paddingMeters: Double): Bounds {
        val lats = points.map { it.lat }
        val lngs = points.map { it.lng }
        val midLat = (lats.min() + lats.max()) / 2.0
        val latPadding = paddingMeters / 111_000.0
        val lngPadding = paddingMeters / (111_000.0 * kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
        return Bounds(
            lats.min() - latPadding,
            lats.max() + latPadding,
            lngs.min() - lngPadding,
            lngs.max() + lngPadding,
        )
    }

    private fun Route.stepDistancesFromStart(): List<Pair<RouteStep, Double>> {
        var distance = 0.0
        return steps.map { step ->
            (step to distance).also { distance += step.distanceMeters }
        }
    }

    private fun RouteStep.primaryEvent(distanceAhead: Double): LookaheadEvent? {
        val roadText = roadText()
        val borderTitle = detectBorderFromStep(roadText)
        return when {
            borderTitle != null -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.BORDER_CROSSING,
                title = borderTitle,
                emoji = "🛂",
                position = startLocation,
                severity = LookaheadSeverity.INFO,
            )
            maneuver == Maneuver.ROUNDABOUT_ENTER || maneuver == Maneuver.ROUNDABOUT_EXIT -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.ROUNDABOUT,
                title = roundaboutTitle(),
                emoji = "⟳",
                position = startLocation,
                severity = LookaheadSeverity.WARNING,
            )
            maneuver in junctionManeuvers -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.JUNCTION,
                title = junctionTitle(),
                emoji = "↱",
                position = startLocation,
                severity = LookaheadSeverity.WARNING,
            )
            maneuver == Maneuver.TUNNEL || roadText.containsAny("tunnel") -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.TUNNEL,
                title = "Tunnel ahead",
                emoji = "🚇",
                position = startLocation,
                severity = LookaheadSeverity.INFO,
            )
            maneuver == Maneuver.FERRY || roadText.containsAny("ferry", "fähre") -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.FERRY,
                title = "Ferry crossing",
                emoji = "⛴",
                position = startLocation,
                severity = LookaheadSeverity.INFO,
            )
            roadText.containsAny("toll", "maut", "péage", "vignette") -> LookaheadEvent(
                distanceMeters = distanceAhead,
                type = LookaheadEventType.TOLL,
                title = "Toll road ahead",
                emoji = "💶",
                position = startLocation,
                severity = LookaheadSeverity.INFO,
            )
            else -> null
        }
    }

    private fun RouteStep.zoneEvents(distanceAhead: Double): List<LookaheadEvent> {
        val text = roadText()
        val limit = speedLimitKph
        val events = mutableListOf<LookaheadEvent>()
        if (limit != null && limit <= 30) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.RESIDENTIAL_ZONE,
                    title = "30 zone",
                    emoji = "30",
                    subtitle = "Slow zone",
                    position = startLocation,
                    severity = LookaheadSeverity.WARNING,
                )
            )
        }
        if (text.containsAny("residential", "wohngebiet", "wohnstraße", "living street", "spielstraße")) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.RESIDENTIAL_ZONE,
                    title = "Residential area",
                    emoji = "🏘",
                    position = startLocation,
                    severity = LookaheadSeverity.INFO,
                )
            )
        }
        if (text.containsAny("traffic calming", "verkehrsberuhigt", "calming")) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.TRAFFIC_CALMING,
                    title = "Traffic calming",
                    emoji = "⚠",
                    subtitle = "Speed humps or narrowed road",
                    position = startLocation,
                    severity = LookaheadSeverity.WARNING,
                )
            )
        }
        if (text.containsAny("school", "schule", "kindergarten")) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.SCHOOL_ZONE,
                    title = "School zone",
                    emoji = "🏫",
                    subtitle = "Children crossing area",
                    position = startLocation,
                    severity = LookaheadSeverity.WARNING,
                )
            )
        }
        if (text.containsAny("lärmschutz", "laermschutz", "noise protection", "noise zone")) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.NOISE_PROTECTION_ZONE,
                    title = "Noise protection zone",
                    emoji = "🔇",
                    position = startLocation,
                    severity = LookaheadSeverity.INFO,
                )
            )
        }
        if (text.hasStopSign()) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.STOP_SIGN,
                    title = "Stop sign",
                    emoji = "STOP",
                    subtitle = "Full stop ahead",
                    position = startLocation,
                    severity = LookaheadSeverity.ALERT,
                )
            )
        }
        if (text.hasGiveWaySign()) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.GIVE_WAY_SIGN,
                    title = "Give way",
                    emoji = "Yield",
                    subtitle = "Yield ahead",
                    position = startLocation,
                    severity = LookaheadSeverity.WARNING,
                )
            )
        }
        if (text.hasPrioritySign()) {
            events.add(
                LookaheadEvent(
                    distanceMeters = distanceAhead,
                    type = LookaheadEventType.PRIORITY_ROAD,
                    title = "Priority road",
                    emoji = "◆",
                    subtitle = "Priority changes ahead",
                    position = startLocation,
                    severity = LookaheadSeverity.INFO,
                )
            )
        }
        return events
    }

    private fun RouteStep.laneEvent(distanceAhead: Double): LookaheadEvent? {
        val guidance = laneGuidance ?: return null
        if (guidance.lanes.isEmpty() || guidance.recommendedIndices.isEmpty()) return null
        val lanes = guidance.recommendedIndices
            .map { it + 1 }
            .joinToString("/")
        val direction = guidance.recommendedIndices
            .firstOrNull()
            ?.let { guidance.lanes.getOrNull(it) }
            ?.directions
            ?.firstOrNull()
            ?.laneLabel()
            ?: "selected lane"
        return LookaheadEvent(
            distanceMeters = distanceAhead,
            type = LookaheadEventType.LANE_GUIDANCE,
            title = "Use lane $lanes · $direction",
            emoji = "▦",
            position = startLocation,
            severity = LookaheadSeverity.WARNING,
        )
    }

    private fun RouteStep.roundaboutTitle(): String =
        exitNumber?.let { "Roundabout exit $it" } ?: "Roundabout ahead"

    private fun RouteStep.junctionTitle(): String = when (maneuver) {
        Maneuver.OFF_RAMP -> "Exit ahead"
        Maneuver.ON_RAMP -> "Ramp ahead"
        Maneuver.FORK_LEFT -> "Keep left at fork"
        Maneuver.FORK_RIGHT -> "Keep right at fork"
        Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> "Merge ahead"
        Maneuver.KEEP_LEFT -> "Keep left"
        Maneuver.KEEP_RIGHT -> "Keep right"
        else -> instruction.ifBlank { "Junction ahead" }
    }

    private fun RouteStep.roadText(): String =
        listOfNotNull(streetName, instruction).joinToString(" ").lowercase()

    private fun detectBorderFromStep(text: String): String? = when {
        text.containsAny("grenze", "border", "frontière", "confine") -> "Border crossing"
        else -> null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun String.hasStopSign(): Boolean =
        containsAny("stop sign", "stoppschild", "stop-schild", "stop at", "stop ahead", "arrêt obligatoire")

    private fun String.hasGiveWaySign(): Boolean =
        containsAny(
            "give way",
            "yield sign",
            "yield ahead",
            "yield to",
            "vorfahrt gewähren",
            "vorfahrt gewaehren",
            "cede priority",
        )

    private fun String.hasPrioritySign(): Boolean =
        containsAny(
            "priority road",
            "priority sign",
            "priority ahead",
            "vorfahrtstraße",
            "vorfahrtstrasse",
            "vorfahrt",
        ) && !hasGiveWaySign()

    private fun LaneDirection.laneLabel(): String = when (this) {
        LaneDirection.STRAIGHT -> "straight"
        LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_LEFT -> "left"
        LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.SHARP_RIGHT -> "right"
        LaneDirection.U_TURN -> "u-turn"
        LaneDirection.MERGE -> "merge"
        LaneDirection.EXIT -> "exit"
    }

    private val junctionManeuvers = setOf(
        Maneuver.TURN_LEFT,
        Maneuver.TURN_RIGHT,
        Maneuver.TURN_SHARP_LEFT,
        Maneuver.TURN_SHARP_RIGHT,
        Maneuver.U_TURN,
        Maneuver.FORK_LEFT,
        Maneuver.FORK_RIGHT,
        Maneuver.MERGE_LEFT,
        Maneuver.MERGE_RIGHT,
        Maneuver.ON_RAMP,
        Maneuver.OFF_RAMP,
        Maneuver.KEEP_LEFT,
        Maneuver.KEEP_RIGHT,
    )

    private fun SpeedCamera.routeMatch(route: Route, routeProgressMeters: Double): RouteCameraMatch? {
        if (route.geometry.size < 2) return null
        var accumulated = 0.0
        var best: RouteCameraMatch? = null
        val cameraPosition = LatLng(lat, lng)

        for (i in 0 until route.geometry.lastIndex) {
            val start = route.geometry[i]
            val end = route.geometry[i + 1]
            val segmentMeters = start.distanceTo(end)
            if (segmentMeters <= 0.0) continue

            val projected = cameraPosition.projectOntoSegment(start, end)
            val distanceFromRoute = cameraPosition.distanceTo(projected)
            val distanceOnSegment = start.distanceTo(projected).coerceIn(0.0, segmentMeters)
            val distanceFromStart = accumulated + distanceOnSegment
            val candidate = RouteCameraMatch(
                distanceAheadMeters = distanceFromStart - routeProgressMeters,
                distanceFromRouteMeters = distanceFromRoute,
            )
            if (best == null || distanceFromRoute < best.distanceFromRouteMeters) {
                best = candidate
            }
            accumulated += segmentMeters
        }

        return best
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

    private data class RouteCameraMatch(
        val distanceAheadMeters: Double,
        val distanceFromRouteMeters: Double,
    )

    private data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)
}
