package com.roadpulse.auto.car

import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverStep
import com.roadpulse.auto.engine.ManeuverType

/**
 * Converts the free-stack [GuidanceState] turn feed into Android Auto's driver-safe turn card
 * (`androidx.car.app.navigation.model`, a neutral Jetpack library, not Google-Maps-specific).
 * GraphHopper has no per-lane data or maneuver bitmap to offer, so `Step`/`Maneuver` are built
 * without `setLanes`/`setLanesImage`/`setIcon`.
 */
object AndroidAutoRoutingInfoFactory {
    fun create(
        state: GuidanceState,
        roadAheadPrimary: String? = null,
        roadAheadSecondary: String? = null,
        junctionImage: CarIcon? = null,
    ): RoutingInfo? {
        if (state.isRerouting || state.hasArrived) return null
        val current = state.currentStep ?: return null
        val currentDistance = state.distanceToNextManeuverMeters ?: 0

        return RoutingInfo
            .Builder()
            .setCurrentStep(
                current.toCarStep(roadAheadPrimary, roadAheadSecondary),
                distance(currentDistance),
            ).apply {
                state.nextStep?.let { setNextStep(it.toCarStep()) }
                junctionImage?.let(::setJunctionImage)
            }.build()
    }

    private fun ManeuverStep.toCarStep(
        roadAheadPrimary: String? = null,
        roadAheadSecondary: String? = null,
    ): Step {
        val instruction = instructionText.takeIf(String::isNotBlank) ?: roadName?.takeIf(String::isNotBlank) ?: "Continue"
        return Step
            .Builder(instruction)
            .setManeuver(toCarManeuver())
            .apply {
                listOfNotNull(
                    roadName?.takeIf(String::isNotBlank),
                    roadAheadPrimary?.takeIf(String::isNotBlank),
                    roadAheadSecondary?.takeIf(String::isNotBlank),
                ).joinToString(" · ").takeIf(String::isNotBlank)?.let(::setRoad)
                // No per-lane data or maneuver bitmap - GraphHopper's Instruction has neither.
            }.build()
    }

    private fun ManeuverStep.toCarManeuver(): Maneuver {
        val type =
            when (maneuver) {
                ManeuverType.DEPART -> Maneuver.TYPE_DEPART
                ManeuverType.STRAIGHT -> Maneuver.TYPE_STRAIGHT
                ManeuverType.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                ManeuverType.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                ManeuverType.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
                ManeuverType.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                ManeuverType.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
                ManeuverType.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
                // GraphHopper's Instruction has one undifferentiated U-turn sign - no
                // clockwise/counterclockwise distinction to pick TYPE_U_TURN_RIGHT instead.
                ManeuverType.U_TURN -> Maneuver.TYPE_U_TURN_LEFT
                // Never actually produced by GraphHopperRoutingEngine's Instruction mapping
                // (GraphHopper has no on/off-ramp sign at all) - handled defensively in case
                // ManeuverType gains a real source for these later.
                ManeuverType.ON_RAMP -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                ManeuverType.OFF_RAMP -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                ManeuverType.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
                ManeuverType.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
                ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
                // GraphHopper's roundabout signs carry no exit angle or direction - the plainest
                // available roundabout icon is used rather than guessing clockwise/counterclockwise.
                ManeuverType.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
                ManeuverType.DESTINATION -> Maneuver.TYPE_DESTINATION
                ManeuverType.UNKNOWN -> Maneuver.TYPE_UNKNOWN
            }
        return Maneuver.Builder(type).build()
    }

    private fun distance(meters: Int): Distance =
        if (meters < 1_000) {
            Distance.create(meters.coerceAtLeast(0).toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1_000.0, Distance.UNIT_KILOMETERS_P1)
        }
}
