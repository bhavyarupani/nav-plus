package com.roadpulse.auto.voice

import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverStep
import com.roadpulse.auto.engine.ManeuverType
import java.util.Locale

/**
 * Pure decision logic for [VoiceGuidance]: given a [GuidanceState], decides which phrase(s) (if
 * any) should be spoken right now, and tracks per-step announcement state so each distance
 * threshold fires at most once per maneuver. Deliberately has no Android dependency (no
 * `TextToSpeech`) so it can be unit-tested directly, matching this codebase's existing separation
 * of pure decision logic from Android I/O (e.g. `SpeedComplianceAdvisor`, `DrivingAttention`).
 */
class VoiceGuidancePlanner {
    private var announcedFarForStep: String? = null
    private var announcedNearForStep: String? = null
    private var announcedImminentForStep: String? = null
    private var announcedArrival = false

    /** Returns zero or one phrase to speak for this state update. */
    fun plan(state: GuidanceState): String? {
        if (state.hasArrived) {
            if (announcedArrival) return null
            announcedArrival = true
            return "You have arrived at your destination"
        }
        announcedArrival = false

        val step = state.currentStep ?: return null
        if (state.isRerouting) return null
        val distance = state.distanceToNextManeuverMeters ?: return null
        val stepKey = stepKey(step)

        if (stepKey != announcedFarForStep) {
            announcedFarForStep = stepKey
            announcedNearForStep = null
            announcedImminentForStep = null
            return when {
                distance > NEAR_THRESHOLD_METERS ->
                    "In ${roundedDistancePhrase(distance)}, ${instructionPhrase(step)}"
                distance > IMMINENT_THRESHOLD_METERS -> {
                    announcedNearForStep = stepKey
                    instructionPhrase(step)
                }
                else -> {
                    announcedNearForStep = stepKey
                    announcedImminentForStep = stepKey
                    "${instructionPhrase(step)} now"
                }
            }
        }

        if (distance <= IMMINENT_THRESHOLD_METERS && announcedImminentForStep != stepKey) {
            announcedImminentForStep = stepKey
            return "${instructionPhrase(step)} now"
        }
        if (distance <= NEAR_THRESHOLD_METERS && announcedNearForStep != stepKey) {
            announcedNearForStep = stepKey
            return instructionPhrase(step)
        }
        return null
    }

    /** Identifies a step by its content, not object identity - a fresh [ManeuverStep] instance is
     * emitted on every [GuidanceState], but it represents the same maneuver until the driver
     * actually reaches it. */
    private fun stepKey(step: ManeuverStep): String = "${step.maneuver}|${step.roadName}|${step.exitNumber}"

    private fun instructionPhrase(step: ManeuverStep): String {
        val base =
            when (step.maneuver) {
                ManeuverType.DEPART -> "head out"
                ManeuverType.STRAIGHT -> "continue straight"
                ManeuverType.TURN_LEFT -> "turn left"
                ManeuverType.TURN_RIGHT -> "turn right"
                ManeuverType.TURN_SLIGHT_LEFT -> "turn slightly left"
                ManeuverType.TURN_SLIGHT_RIGHT -> "turn slightly right"
                ManeuverType.TURN_SHARP_LEFT -> "make a sharp left turn"
                ManeuverType.TURN_SHARP_RIGHT -> "make a sharp right turn"
                ManeuverType.U_TURN -> "make a U-turn"
                ManeuverType.ON_RAMP -> "take the ramp"
                ManeuverType.OFF_RAMP -> "take the exit"
                ManeuverType.FORK_LEFT -> "keep left at the fork"
                ManeuverType.FORK_RIGHT -> "keep right at the fork"
                ManeuverType.MERGE -> "merge"
                ManeuverType.ROUNDABOUT -> "enter the roundabout"
                ManeuverType.DESTINATION -> "arrive at your destination"
                ManeuverType.UNKNOWN -> step.instructionText.ifBlank { "continue" }
            }
        val exit = step.exitNumber?.let { " onto exit $it" }
        val road = step.roadName?.takeIf(String::isNotBlank)?.let { " onto $it" }
        return base + (exit ?: road).orEmpty()
    }

    private fun roundedDistancePhrase(distanceMeters: Int): String {
        val rounded = ((distanceMeters + 25) / 50) * 50
        return if (rounded >= 1_000) {
            "%.1f kilometers".format(Locale.US, rounded / 1_000.0)
        } else {
            "$rounded meters"
        }
    }

    private companion object {
        const val NEAR_THRESHOLD_METERS = 150
        const val IMMINENT_THRESHOLD_METERS = 30
    }
}
