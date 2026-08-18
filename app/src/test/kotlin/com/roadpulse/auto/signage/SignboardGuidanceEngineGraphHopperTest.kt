package com.roadpulse.auto.signage

import com.roadpulse.auto.driving.RouteMatchConfidence
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverStep
import com.roadpulse.auto.engine.ManeuverType
import com.roadpulse.auto.traffic.LaneTopologyWaySection
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [SignboardGuidanceEngine]'s `GuidanceState`-based overload (the free-stack/GraphHopper
 * path) - see that overload's doc for why its gating differs from the Google `NavInfo` one
 * covered by [SignboardGuidanceEngineTest]: GraphHopper has no off-ramp/exit sign at all, so the
 * gate is primarily "is there a matched OSM motorway_junction", not the routing engine's own
 * maneuver classification. */
class SignboardGuidanceEngineGraphHopperTest {
    private fun step(
        maneuver: ManeuverType = ManeuverType.FORK_RIGHT,
        roadName: String? = "A 8",
    ): ManeuverStep =
        ManeuverStep(
            maneuver = maneuver,
            instructionText = "Keep right",
            roadName = roadName,
            exitNumber = null,
            distanceMeters = 500,
        )

    private fun guidanceState(
        step: ManeuverStep? = step(),
        distanceToNextManeuverMeters: Int? = 500,
        isRerouting: Boolean = false,
        hasArrived: Boolean = false,
    ): GuidanceState =
        GuidanceState(
            currentStep = step,
            nextStep = null,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            distanceToDestinationMeters = 10_000,
            etaEpochSeconds = null,
            isRerouting = isRerouting,
            hasArrived = hasArrived,
        )

    private fun junctionFeature(
        exitNumber: String? = "54",
        distanceMeters: Int = 520,
        detail: String = "Exit 54 · toward Esslingen · OpenStreetMap",
        confidence: RouteMatchConfidence = RouteMatchConfidence.HIGH,
    ): UpcomingRouteRoadFeature =
        UpcomingRouteRoadFeature(
            point =
                RoadInfrastructurePoint(
                    id = "node/1",
                    coordinate = RoadCoordinate(49.0, 9.0),
                    type = RoadInfrastructureType.MOTORWAY_JUNCTION,
                    title = "Exit $exitNumber",
                    detail = detail,
                    direction = null,
                    trafficSignCode = null,
                    exitNumber = exitNumber,
                ),
            distanceMeters = distanceMeters,
            confidence = confidence,
        )

    private fun autobahnWaySection(turnLanes: String = "through|through|slight_right") =
        LaneTopologyWaySection(
            id = "way/1",
            geometry = listOf(RoadCoordinate(48.999, 9.0), RoadCoordinate(49.0, 9.0)),
            ref = "A 8",
            lanes = "3",
            lanesForward = null,
            turnLanes = turnLanes,
            destinationLanes = "||Esslingen",
            destinationRefLanes = null,
            changeLanes = null,
        )

    @Test
    fun `no active step yields no signboard`() {
        val guidance = SignboardGuidanceEngine.build(guidanceState(step = null), emptyList(), emptyList())
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `rerouting yields no signboard`() {
        val guidance = SignboardGuidanceEngine.build(guidanceState(isRerouting = true), listOf(junctionFeature()), emptyList())
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `having arrived yields no signboard`() {
        val guidance = SignboardGuidanceEngine.build(guidanceState(hasArrived = true), listOf(junctionFeature()), emptyList())
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `no matched OSM junction and a plain straight maneuver yields no signboard`() {
        // GraphHopper has no exit/off-ramp sign of its own - without an OSM motorway_junction
        // match there is nothing reliable to show, unlike the Google overload which can still
        // fall back to Google's own per-lane data (GraphHopper has none).
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step(maneuver = ManeuverType.STRAIGHT)),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `a fork signal with no OSM junction match produces no reliable panel`() {
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step(maneuver = ManeuverType.FORK_RIGHT)),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardFallbackLevel.STANDARD_MANEUVER, guidance.fallbackLevel)
        assertNull(guidance.junction)
    }

    @Test
    fun `a matched Autobahn junction with lane topology produces a full signboard from OSM alone`() {
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step()),
                listOf(junctionFeature()),
                listOf(autobahnWaySection()),
            )
        assertEquals(SignboardFallbackLevel.FULL_SIGNBOARD, guidance.fallbackLevel)
        val junction = requireNotNull(guidance.junction)
        val panel = junction.panels.single()
        assertEquals(SignboardType.AUTOBAHN_BLUE, panel.type)
        assertEquals("54", panel.exitNumber)
        assertTrue(panel.destinations.any { it.text == "Esslingen" })
        assertEquals(GuidanceDataSource.OPENSTREETMAP, junction.source)
        assertEquals(GuidanceConfidence.HIGH, junction.confidence)
    }

    @Test
    fun `lane guidance source is never blended - GraphHopper contributes no per-lane data`() {
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step()),
                listOf(junctionFeature()),
                listOf(autobahnWaySection()),
            )
        assertEquals(GuidanceDataSource.OPENSTREETMAP, guidance.junction?.laneGuidance?.source)
    }

    @Test
    fun `a junction whose distance materially disagrees with GraphHopper's step is not used`() {
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step(), distanceToNextManeuverMeters = 500),
                listOf(junctionFeature(distanceMeters = 3_000)),
                listOf(autobahnWaySection()),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `a step further ahead than the lookahead window yields no signboard`() {
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step(), distanceToNextManeuverMeters = 5_000),
                listOf(junctionFeature(distanceMeters = 5_000)),
                emptyList(),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `a fork-left maneuver resolves lanes via the left turn-lane tokens`() {
        val leftWaySection = autobahnWaySection(turnLanes = "slight_left|through|through")
        val guidance =
            SignboardGuidanceEngine.build(
                guidanceState(step = step(maneuver = ManeuverType.FORK_LEFT)),
                listOf(junctionFeature()),
                listOf(leftWaySection),
            )
        val lanes =
            guidance.junction
                ?.laneGuidance
                ?.lanes
                .orEmpty()
        // A lane whose only turn:lanes token is "slight_left" (no "through" option) exclusively
        // serves the matched exit - EXIT_ONLY, not RECOMMENDED (which is reserved for a lane that
        // also continues through). See LaneTopologyParser.laneState.
        assertTrue(lanes.isNotEmpty())
        assertEquals(LaneState.EXIT_ONLY, lanes.first().state)
    }
}
