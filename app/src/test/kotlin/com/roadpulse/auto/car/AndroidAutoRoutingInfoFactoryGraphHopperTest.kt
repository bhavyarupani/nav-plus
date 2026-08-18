package com.roadpulse.auto.car

import androidx.car.app.navigation.model.Maneuver
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverStep
import com.roadpulse.auto.engine.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [AndroidAutoRoutingInfoFactory]'s `GuidanceState`-based `create` overload (the
 * free-stack/GraphHopper path). */
class AndroidAutoRoutingInfoFactoryGraphHopperTest {
    private fun step(
        maneuver: ManeuverType = ManeuverType.TURN_RIGHT,
        instructionText: String = "Turn right",
        roadName: String? = "A 8",
    ): ManeuverStep =
        ManeuverStep(
            maneuver = maneuver,
            instructionText = instructionText,
            roadName = roadName,
            exitNumber = null,
            distanceMeters = 300,
        )

    private fun guidanceState(
        currentStep: ManeuverStep? = step(),
        nextStep: ManeuverStep? = null,
        distanceToNextManeuverMeters: Int? = 400,
        isRerouting: Boolean = false,
        hasArrived: Boolean = false,
    ): GuidanceState =
        GuidanceState(
            currentStep = currentStep,
            nextStep = nextStep,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            distanceToDestinationMeters = 5_000,
            etaEpochSeconds = null,
            isRerouting = isRerouting,
            hasArrived = hasArrived,
        )

    @Test
    fun `no current step yields no routing info`() {
        assertNull(AndroidAutoRoutingInfoFactory.create(guidanceState(currentStep = null)))
    }

    @Test
    fun `rerouting yields no routing info`() {
        assertNull(AndroidAutoRoutingInfoFactory.create(guidanceState(isRerouting = true)))
    }

    @Test
    fun `having arrived yields no routing info`() {
        assertNull(AndroidAutoRoutingInfoFactory.create(guidanceState(hasArrived = true)))
    }

    @Test
    fun `a current step produces routing info with the current step's instruction`() {
        val info = AndroidAutoRoutingInfoFactory.create(guidanceState())
        assertEquals("Turn right", info?.currentStep?.cue?.toString())
    }

    @Test
    fun `a next step is included when present`() {
        val info =
            AndroidAutoRoutingInfoFactory.create(
                guidanceState(nextStep = step(instructionText = "Then turn left", maneuver = ManeuverType.TURN_LEFT)),
            )
        assertEquals("Then turn left", info?.nextStep?.cue?.toString())
    }

    @Test
    fun `a fork-right maneuver maps to the fork-right car maneuver type`() {
        val info = AndroidAutoRoutingInfoFactory.create(guidanceState(currentStep = step(maneuver = ManeuverType.FORK_RIGHT)))
        assertEquals(Maneuver.TYPE_FORK_RIGHT, info?.currentStep?.maneuver?.type)
    }

    @Test
    fun `a destination maneuver maps to the destination car maneuver type`() {
        val info = AndroidAutoRoutingInfoFactory.create(guidanceState(currentStep = step(maneuver = ManeuverType.DESTINATION)))
        assertEquals(Maneuver.TYPE_DESTINATION, info?.currentStep?.maneuver?.type)
    }

    @Test
    fun `road-ahead text is appended to the step's road description`() {
        val info =
            AndroidAutoRoutingInfoFactory.create(
                guidanceState(),
                roadAheadPrimary = "Speed camera ahead",
            )
        assertEquals("A 8 · Speed camera ahead", info?.currentStep?.road?.toString())
    }
}
