package com.navplus.core.group

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import com.navplus.core.group.model.GroupMember
import com.navplus.core.group.model.RejoinInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Singleton
class ConvoyEngine @Inject constructor() {

    fun detectDeviation(memberLocation: LatLng, route: Route, thresholdMeters: Double = 150.0): Boolean {
        val nearest = nearestPointOnRoute(memberLocation, route).point
        return memberLocation.distanceTo(nearest) > thresholdMeters
    }

    fun calculateRejoinInfo(
        deviatedLocation: LatLng,
        deviatedSpeedKph: Float,
        route: Route,
    ): RejoinInfo {
        val nearest = nearestPointOnRoute(deviatedLocation, route)
        // Skip forward to find a point where the member can reasonably rejoin
        val rejoinPoint = route.geometry[min(nearest.segmentEndIndex + 5, route.geometry.lastIndex)]
        val directDistance = deviatedLocation.distanceTo(rejoinPoint)
        val speedMs = if (deviatedSpeedKph > 0) deviatedSpeedKph / 3.6 else 13.9 // 50 km/h default
        val etaSec = (directDistance / speedMs).toLong()

        return RejoinInfo(
            distanceMeters = directDistance,
            etaSec = etaSec,
            locationName = null, // Would be reverse-geocoded in a full impl
        )
    }

    fun groupEtaSec(members: Collection<GroupMember>): Long? =
        members.mapNotNull { it.etaSec }.maxOrNull()

    private fun nearestPointOnRoute(point: LatLng, route: Route): RouteNearestPoint {
        if (route.geometry.size < 2) {
            return RouteNearestPoint(route.geometry.firstOrNull() ?: LatLng.ZERO, 0)
        }
        var nearest = route.geometry.first()
        var nearestSegmentEnd = 1
        var minDist = Double.MAX_VALUE
        for (index in 0 until route.geometry.lastIndex) {
            val projected = projectToSegment(point, route.geometry[index], route.geometry[index + 1])
            val d = point.distanceTo(projected)
            if (d < minDist) {
                minDist = d
                nearest = projected
                nearestSegmentEnd = index + 1
            }
        }
        return RouteNearestPoint(nearest, nearestSegmentEnd)
    }

    private fun projectToSegment(point: LatLng, start: LatLng, end: LatLng): LatLng {
        val originLat = Math.toRadians(start.lat)
        val metersPerLat = 111_320.0
        val metersPerLng = metersPerLat * cos(originLat)
        val px = (point.lng - start.lng) * metersPerLng
        val py = (point.lat - start.lat) * metersPerLat
        val sx = (end.lng - start.lng) * metersPerLng
        val sy = (end.lat - start.lat) * metersPerLat
        val lenSq = sx.pow(2.0) + sy.pow(2.0)
        if (lenSq <= 0.0) return start
        val t = ((px * sx + py * sy) / lenSq).coerceIn(0.0, 1.0)
        return LatLng(
            lat = start.lat + ((sy * t) / metersPerLat),
            lng = start.lng + ((sx * t) / max(metersPerLng, 1.0)),
        )
    }

    private data class RouteNearestPoint(
        val point: LatLng,
        val segmentEndIndex: Int,
    )
}
