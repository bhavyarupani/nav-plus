package com.roadpulse.auto.voice

import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverStep
import com.roadpulse.auto.engine.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGuidancePlannerTest {
    private fun step(
        maneuver: ManeuverType = ManeuverType.TURN_LEFT,
        roadName: String? = "Foo Street",
        exitNumber: String? = null,
    ): ManeuverStep =
        ManeuverStep(
            maneuver = maneuver,
            instructionText = "Turn left",
            roadName = roadName,
            exitNumber = exitNumber,
            distanceMeters = 300,
        )

    private fun state(
        currentStep: ManeuverStep? = step(),
        distanceToNextManeuverMeters: Int? = 400,
        isRerouting: Boolean = false,
        hasArrived: Boolean = false,
    ): GuidanceState =
        GuidanceState(
            currentStep = currentStep,
            nextStep = null,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            distanceToDestinationMeters = 1000,
            etaEpochSeconds = null,
            isRerouting = isRerouting,
            hasArrived = hasArrived,
        )

    @Test
    fun `far announcement includes distance and instruction`() {
        val planner = VoiceGuidancePlanner()
        val phrase = planner.plan(state(distanceToNextManeuverMeters = 400))
        assertEquals("In 400 meters, turn left onto Foo Street", phrase)
    }

    @Test
    fun `same step does not repeat the far announcement on the next update`() {
        val planner = VoiceGuidancePlanner()
        planner.plan(state(distanceToNextManeuverMeters = 400))
        val second = planner.plan(state(distanceToNextManeuverMeters = 380))
        assertNull(second)
    }

    @Test
    fun `crossing the near threshold announces once`() {
        val planner = VoiceGuidancePlanner()
        planner.plan(state(distanceToNextManeuverMeters = 400))
        val near = planner.plan(state(distanceToNextManeuverMeters = 140))
        assertEquals("turn left onto Foo Street", near)
        // Should not repeat on the next update while still inside the near band.
        assertNull(planner.plan(state(distanceToNextManeuverMeters = 120)))
    }

    @Test
    fun `crossing the imminent threshold announces once with now`() {
        val planner = VoiceGuidancePlanner()
        planner.plan(state(distanceToNextManeuverMeters = 400))
        planner.plan(state(distanceToNextManeuverMeters = 140))
        val imminent = planner.plan(state(distanceToNextManeuverMeters = 25))
        assertEquals("turn left onto Foo Street now", imminent)
        assertNull(planner.plan(state(distanceToNextManeuverMeters = 10)))
    }

    @Test
    fun `a step starting inside the near band skips straight to the near phrase`() {
        val planner = VoiceGuidancePlanner()
        val phrase = planner.plan(state(distanceToNextManeuverMeters = 100))
        assertEquals("turn left onto Foo Street", phrase)
    }

    @Test
    fun `a new step after the previous one resets announcement tracking`() {
        val planner = VoiceGuidancePlanner()
        planner.plan(state(currentStep = step(roadName = "Foo Street"), distanceToNextManeuverMeters = 400))
        planner.plan(state(currentStep = step(roadName = "Foo Street"), distanceToNextManeuverMeters = 140))

        val nextStepPhrase =
            planner.plan(
                state(
                    currentStep = step(maneuver = ManeuverType.TURN_RIGHT, roadName = "Bar Street"),
                    distanceToNextManeuverMeters = 500,
                ),
            )
        assertEquals("In 500 meters, turn right onto Bar Street", nextStepPhrase)
    }

    @Test
    fun `rerouting suppresses announcements`() {
        val planner = VoiceGuidancePlanner()
        val phrase = planner.plan(state(distanceToNextManeuverMeters = 400, isRerouting = true))
        assertNull(phrase)
    }

    @Test
    fun `arrival announces exactly once`() {
        val planner = VoiceGuidancePlanner()
        val first = planner.plan(state(hasArrived = true))
        val second = planner.plan(state(hasArrived = true))
        assertEquals("You have arrived at your destination", first)
        assertNull(second)
    }

    @Test
    fun `an exit number is announced instead of the road name`() {
        val planner = VoiceGuidancePlanner()
        val phrase =
            planner.plan(
                state(
                    currentStep =
                        step(maneuver = ManeuverType.OFF_RAMP, roadName = "A 8", exitNumber = "54"),
                    distanceToNextManeuverMeters = 400,
                ),
            )
        assertTrue(phrase!!.contains("onto exit 54"))
        assertTrue(!phrase.contains("onto A 8"))
    }
}
