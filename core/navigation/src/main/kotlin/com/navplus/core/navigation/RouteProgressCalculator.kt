package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo

object RouteProgressCalculator {
    private const val OFF_ROUTE_THRESHOLD_M = 50.0
    private const val STEP_COMPLETION_THRESHOLD_M = 20.0

    fun updateProgress(
        prev: RouteProgress,
        location: Location,
        route: Route,
    ): RouteProgress {
        if (route.steps.isEmpty() || route.geometry.isEmpty()) return prev
        val snapped = snapToRoute(location.latLng, route)
        val stepIndex = findCurrentStep(snapped.point, route, prev.currentStepIndex)
        if (stepIndex !in route.steps.indices) return prev
        val step = route.steps[stepIndex]
        val distToStep = snapped.point.distanceTo(step.endLocation)
        val distRemaining = route.steps.drop(stepIndex + 1).sumOf { it.distanceMeters } + distToStep
        val durRemaining = if (route.distanceMeters > 0.0) {
            (distRemaining / route.distanceMeters * route.durationSeconds).toLong()
        } else {
            0L
        }

        val offRouteThreshold = maxOf(OFF_ROUTE_THRESHOLD_M, location.accuracyMeters * 2.5)
        val offRoute = snapped.point.distanceTo(location.latLng) > offRouteThreshold

        return prev.copy(
            currentStepIndex = stepIndex,
            distanceToNextStepMeters = distToStep,
            distanceRemainingMeters = distRemaining,
            durationRemainingSeconds = durRemaining,
            snappedLocation = snapped.point,
            routeBearingDeg = snapped.bearingDeg,
            nextManeuver = step.maneuver,
            nextInstruction = step.instruction,
            nextStreetName = step.streetName,
            laneGuidance = step.laneGuidance,
            signboard = step.signboard,
            speedLimitKph = step.speedLimitKph,
            isOffRoute = offRoute,
        )
    }

    fun initialBearing(route: Route): Float = route.segmentBearing(0)

    private fun snapToRoute(position: LatLng, route: Route): SnappedPosition {
        var closest = route.geometry.first()
        var minDist = Double.MAX_VALUE
        var closestSegmentIndex = 0
        for (i in 0 until route.geometry.size - 1) {
            val p = projectOntoSegment(position, route.geometry[i], route.geometry[i + 1])
            val d = position.distanceTo(p)
            if (d < minDist) {
                minDist = d
                closest = p
                closestSegmentIndex = i
            }
        }
        return SnappedPosition(
            point = closest,
            bearingDeg = route.segmentBearing(closestSegmentIndex),
        )
    }

    private fun projectOntoSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val ax = b.lng - a.lng
        val ay = b.lat - a.lat
        val denom = ax * ax + ay * ay
        if (denom == 0.0) return a
        val t = ((p.lng - a.lng) * ax + (p.lat - a.lat) * ay) / denom
        val tc = t.coerceIn(0.0, 1.0)
        return LatLng(a.lat + tc * ay, a.lng + tc * ax)
    }

    private fun findCurrentStep(location: LatLng, route: Route, hintIndex: Int): Int {
        val steps = route.steps
        if (steps.isEmpty()) return 0
        val start = hintIndex.coerceIn(0, steps.lastIndex)
        for (i in start until steps.size) {
            if (location.distanceTo(steps[i].endLocation) < STEP_COMPLETION_THRESHOLD_M) continue
            return i
        }
        return steps.lastIndex
    }

    private fun Route.segmentBearing(index: Int): Float {
        if (geometry.size < 2) return 0f
        val i = index.coerceIn(0, geometry.lastIndex - 1)
        return geometry[i].bearingTo(geometry[i + 1]).toFloat()
    }

    private data class SnappedPosition(
        val point: LatLng,
        val bearingDeg: Float,
    )
}
