package com.roadpulse.auto.car

import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.core.graphics.drawable.IconCompat
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import androidx.car.app.navigation.model.Lane as CarLane
import androidx.car.app.navigation.model.LaneDirection as CarLaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver

/** Converts Google's authoritative turn and lane feed into Android Auto's driver-safe turn card. */
object AndroidAutoRoutingInfoFactory {
    fun create(
        navInfo: NavInfo,
        roadAheadPrimary: String? = null,
        roadAheadSecondary: String? = null,
        junctionImage: CarIcon? = null,
    ): RoutingInfo? {
        if (navInfo.navState != NavState.ENROUTE) return null
        val current = navInfo.currentStep ?: return null
        val currentDistance = navInfo.distanceToCurrentStepMeters ?: 0

        return RoutingInfo
            .Builder()
            .setCurrentStep(
                current.toCarStep(
                    roadAheadPrimary,
                    roadAheadSecondary,
                ),
                distance(currentDistance),
            ).apply {
                navInfo.remainingSteps.firstOrNull()?.let { setNextStep(it.toCarStep()) }
                junctionImage?.let(::setJunctionImage)
            }.build()
    }

    private fun StepInfo.toCarStep(
        roadAheadPrimary: String? = null,
        roadAheadSecondary: String? = null,
    ): Step {
        val instruction =
            fullInstructionText
                ?.takeIf { it.isNotBlank() }
                ?: fullRoadName?.takeIf { it.isNotBlank() }
                ?: "Continue"
        return Step
            .Builder(instruction)
            .setManeuver(toCarManeuver())
            .apply {
                listOfNotNull(
                    fullRoadName?.takeIf { it.isNotBlank() },
                    roadAheadPrimary?.takeIf { it.isNotBlank() },
                    roadAheadSecondary?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf(String::isNotBlank)?.let(::setRoad)
                lanes.orEmpty().forEach { googleLane ->
                    val carLane = CarLane.Builder()
                    googleLane.laneDirections().forEach { direction ->
                        carLane.addDirection(
                            CarLaneDirection.create(
                                direction.laneShape().toCarLaneShape(),
                                direction.isRecommended == true,
                            ),
                        )
                    }
                    addLane(carLane.build())
                }
                lanesBitmap?.let { bitmap ->
                    setLanesImage(
                        CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build(),
                    )
                }
            }.build()
    }

    private fun StepInfo.toCarManeuver(): Maneuver {
        val type =
            when (maneuver) {
                GoogleManeuver.DEPART -> Maneuver.TYPE_DEPART
                GoogleManeuver.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
                GoogleManeuver.STRAIGHT -> Maneuver.TYPE_STRAIGHT
                GoogleManeuver.TURN_KEEP_LEFT,
                GoogleManeuver.ON_RAMP_KEEP_LEFT,
                GoogleManeuver.OFF_RAMP_KEEP_LEFT,
                -> Maneuver.TYPE_KEEP_LEFT
                GoogleManeuver.TURN_KEEP_RIGHT,
                GoogleManeuver.ON_RAMP_KEEP_RIGHT,
                GoogleManeuver.OFF_RAMP_KEEP_RIGHT,
                -> Maneuver.TYPE_KEEP_RIGHT
                GoogleManeuver.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                GoogleManeuver.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                GoogleManeuver.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
                GoogleManeuver.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                GoogleManeuver.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
                GoogleManeuver.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
                GoogleManeuver.TURN_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_U_TURN_LEFT
                GoogleManeuver.TURN_U_TURN_CLOCKWISE -> Maneuver.TYPE_U_TURN_RIGHT
                GoogleManeuver.ON_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
                GoogleManeuver.ON_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
                GoogleManeuver.ON_RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                GoogleManeuver.ON_RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                GoogleManeuver.ON_RAMP_SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
                GoogleManeuver.ON_RAMP_SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
                GoogleManeuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
                GoogleManeuver.ON_RAMP_U_TURN_CLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
                GoogleManeuver.OFF_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                GoogleManeuver.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                GoogleManeuver.OFF_RAMP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                GoogleManeuver.OFF_RAMP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                GoogleManeuver.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
                GoogleManeuver.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
                GoogleManeuver.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
                GoogleManeuver.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
                GoogleManeuver.MERGE_UNSPECIFIED -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
                GoogleManeuver.DESTINATION -> Maneuver.TYPE_DESTINATION
                GoogleManeuver.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
                GoogleManeuver.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
                GoogleManeuver.FERRY_BOAT -> Maneuver.TYPE_FERRY_BOAT
                GoogleManeuver.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
                else -> Maneuver.TYPE_UNKNOWN
            }
        return Maneuver
            .Builder(type)
            .apply {
                maneuverBitmap?.let { bitmap ->
                    setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
                }
            }.build()
    }

    private fun distance(meters: Int): Distance =
        if (meters < 1_000) {
            Distance.create(meters.coerceAtLeast(0).toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1_000.0, Distance.UNIT_KILOMETERS_P1)
        }

    private fun Int.toCarLaneShape(): Int =
        when (this) {
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.STRAIGHT ->
                CarLaneDirection.SHAPE_STRAIGHT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.SLIGHT_LEFT ->
                CarLaneDirection.SHAPE_SLIGHT_LEFT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.SLIGHT_RIGHT ->
                CarLaneDirection.SHAPE_SLIGHT_RIGHT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.NORMAL_LEFT ->
                CarLaneDirection.SHAPE_NORMAL_LEFT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.NORMAL_RIGHT ->
                CarLaneDirection.SHAPE_NORMAL_RIGHT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.SHARP_LEFT ->
                CarLaneDirection.SHAPE_SHARP_LEFT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.SHARP_RIGHT ->
                CarLaneDirection.SHAPE_SHARP_RIGHT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.U_TURN_LEFT ->
                CarLaneDirection.SHAPE_U_TURN_LEFT
            com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape.U_TURN_RIGHT ->
                CarLaneDirection.SHAPE_U_TURN_RIGHT
            else -> CarLaneDirection.SHAPE_UNKNOWN
        }
}
