package com.navplus.core.group

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import com.navplus.core.group.model.GroupMember
import com.navplus.core.group.model.RejoinInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ConvoyEngine @Inject constructor() {

    fun detectDeviation(memberLocation: LatLng, route: Route, thresholdMeters: Double = 150.0): Boolean {
        val nearest = nearestPointOnRoute(memberLocation, route)
        return memberLocation.distanceTo(nearest) > thresholdMeters
    }

    fun calculateRejoinInfo(
        deviatedLocation: LatLng,
        deviatedSpeedKph: Float,
        route: Route,
    ): RejoinInfo {
        // Find the nearest point on the route ahead of the deviation
        var minDist = Double.MAX_VALUE
        var rejoinIndex = 0
        for (i in route.geometry.indices) {
            val d = deviatedLocation.distanceTo(route.geometry[i])
            if (d < minDist) {
                minDist = d
                rejoinIndex = i
            }
        }
        // Skip forward to find a point where the member can reasonably rejoin
        val rejoinPoint = route.geometry[min(rejoinIndex + 5, route.geometry.lastIndex)]
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

    private fun nearestPointOnRoute(point: LatLng, route: Route): LatLng {
        var nearest = route.geometry.first()
        var minDist = Double.MAX_VALUE
        for (p in route.geometry) {
            val d = point.distanceTo(p)
            if (d < minDist) { minDist = d; nearest = p }
        }
        return nearest
    }
}
