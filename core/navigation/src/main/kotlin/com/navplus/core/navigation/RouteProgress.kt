package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.Signboard

data class RouteProgress(
    val route: Route,
    val currentStepIndex: Int,
    val distanceToNextStepMeters: Double,
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Long,
    val snappedLocation: LatLng,
    val nextManeuver: Maneuver,
    val nextInstruction: String,
    val nextStreetName: String?,
    val laneGuidance: LaneGuidance?,
    val signboard: Signboard?,
    val isOffRoute: Boolean = false,
    val speedLimitKph: Int? = null,
) {
    val currentStep: RouteStep get() = route.steps[currentStepIndex]
    val hasLaneGuidance: Boolean get() = laneGuidance != null
    val isApproachingManeuver: Boolean get() = distanceToNextStepMeters < 300
}
