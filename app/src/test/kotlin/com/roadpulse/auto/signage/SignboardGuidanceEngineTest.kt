package com.roadpulse.auto.signage

import com.google.android.libraries.mapsplatform.turnbyturn.model.Lane
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.roadpulse.auto.driving.RouteMatchConfidence
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.traffic.LaneTopologyWaySection
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver

class SignboardGuidanceEngineTest {
    private fun step(
        maneuver: Int = GoogleManeuver.OFF_RAMP_RIGHT,
        fullRoadName: String? = "A 8",
        exitNumber: String? = "54",
        lanes: List<Lane> = emptyList(),
    ): StepInfo =
        StepInfo(
            maneuver,
            fullRoadName,
            fullRoadName,
            "Take the exit",
            exitNumber,
            0,
            null,
            null,
            null,
            null,
            lanes,
            null,
            null,
        )

    private fun navInfo(
        step: StepInfo?,
        distanceMeters: Int? = 500,
        routeChanged: Boolean = false,
        navState: Int = NavState.ENROUTE,
    ): NavInfo =
        NavInfo
            .builder()
            .setNavState(navState)
            .setCurrentStep(step)
            .setRemainingSteps(emptyArray())
            .setRouteChanged(routeChanged)
            .setDistanceToCurrentStepMeters(distanceMeters)
            .build()

    private fun lane(
        recommended: Boolean?,
        shape: Int = LaneDirection.LaneShape.STRAIGHT,
    ): Lane =
        Lane
            .builder()
            .setLaneDirections(
                listOf(
                    LaneDirection
                        .builder()
                        .setLaneShape(shape)
                        .setIsRecommended(recommended)
                        .build(),
                ),
            ).build()

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

    @Test
    fun `no active step yields no signboard`() {
        val guidance = SignboardGuidanceEngine.build(navInfo(step = null), emptyList(), emptyList())
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `not enroute yields no signboard`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(), navState = NavState.STOPPED),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `a rerouting event clears any prior signboard immediately`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(), routeChanged = true),
                listOf(junctionFeature()),
                emptyList(),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }

    @Test
    fun `an unmatched opposite-direction junction is never used - no invented enrichment appears`() {
        // RouteRoadFeatureAnalyzer already rejects opposite-direction points before they reach
        // the engine, so an empty upcoming-features list is exactly what that filtering produces.
        // With no Google exit number either, there is nothing reliable to show at all.
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(exitNumber = null)),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardFallbackLevel.STANDARD_MANEUVER, guidance.fallbackLevel)
        assertNull(guidance.junction)
    }

    @Test
    fun `matched Autobahn exit produces a blue full signboard when lane topology is also known`() {
        val waySection =
            LaneTopologyWaySection(
                id = "way/1",
                geometry = listOf(RoadCoordinate(48.999, 9.0), RoadCoordinate(49.0, 9.0)),
                ref = "A 8",
                lanes = "3",
                lanesForward = null,
                turnLanes = "through|through|slight_right",
                destinationLanes = "||Esslingen",
                destinationRefLanes = null,
                changeLanes = null,
            )
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step()),
                listOf(junctionFeature()),
                listOf(waySection),
            )
        assertEquals(SignboardFallbackLevel.FULL_SIGNBOARD, guidance.fallbackLevel)
        val panel = requireNotNull(guidance.junction).panels.single()
        assertEquals(SignboardType.AUTOBAHN_BLUE, panel.type)
        assertEquals("54", panel.exitNumber)
        assertTrue(panel.destinations.any { it.text == "Esslingen" })
        assertEquals(GuidanceConfidence.HIGH, guidance.junction!!.confidence)
    }

    @Test
    fun `conflicting Google and OSM exit numbers trust Google and drop OSM enrichment`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(exitNumber = "54")),
                listOf(junctionFeature(exitNumber = "53", distanceMeters = 500)),
                emptyList(),
            )
        val panels = guidance.junction?.panels.orEmpty()
        if (panels.isNotEmpty()) {
            assertEquals("54", panels.single().exitNumber)
            assertTrue(panels.single().destinations.isEmpty())
        }
        assertEquals(GuidanceConfidence.LOW, guidance.junction?.confidence ?: GuidanceConfidence.LOW)
    }

    @Test
    fun `a junction whose distance materially disagrees with Google's step is not used`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(), distanceMeters = 500),
                listOf(junctionFeature(distanceMeters = 3_000)),
                emptyList(),
            )
        // Google's own exit number (from the step) may still surface, but nothing from the
        // mismatched OSM junction - the "toward Esslingen" destination text - is allowed through.
        assertTrue(
            guidance.junction
                ?.panels
                .orEmpty()
                .all { it.destinations.isEmpty() },
        )
    }

    @Test
    fun `uncertain lane count falls back to junction view instead of a full signboard`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step()),
                listOf(junctionFeature()),
                emptyList(),
            )
        assertEquals(SignboardFallbackLevel.JUNCTION_VIEW, guidance.fallbackLevel)
    }

    @Test
    fun `Google lane data alone without a matched junction yields simple lane arrows`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(maneuver = GoogleManeuver.STRAIGHT, exitNumber = null, lanes = listOf(lane(true)))),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardFallbackLevel.SIMPLE_LANE_ARROWS, guidance.fallbackLevel)
        assertEquals(
            LaneState.RECOMMENDED,
            guidance.junction
                ?.laneGuidance
                ?.lanes
                ?.single()
                ?.state,
        )
    }

    @Test
    fun `no lane data and no junction match falls all the way back to the standard maneuver`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(maneuver = GoogleManeuver.STRAIGHT, exitNumber = null)),
                emptyList(),
                emptyList(),
            )
        assertEquals(SignboardFallbackLevel.STANDARD_MANEUVER, guidance.fallbackLevel)
        assertNull(guidance.junction)
    }

    @Test
    fun `a merge onto a differently-numbered Autobahn from a minor road renders as mixed with an inset`() {
        val waySection =
            LaneTopologyWaySection(
                id = "way/2",
                geometry = listOf(RoadCoordinate(48.999, 9.0), RoadCoordinate(49.0, 9.0)),
                ref = "B 10",
                lanes = "1",
                lanesForward = null,
                turnLanes = "slight_right",
                destinationLanes = "A 8 Stuttgart",
                destinationRefLanes = null,
                changeLanes = null,
            )
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(maneuver = GoogleManeuver.MERGE_RIGHT, fullRoadName = "B 10", exitNumber = null)),
                listOf(junctionFeature(exitNumber = null, detail = "toward Stuttgart · OpenStreetMap")),
                listOf(waySection),
            )
        val panel = guidance.junction?.panels?.singleOrNull()
        assertEquals(SignboardType.MIXED, panel?.type)
        assertEquals("B 10", panel?.roadRef)
        assertEquals("A 8", panel?.insetRoadRef)
    }

    @Test
    fun `merging onto a road makes every known lane MERGING rather than individually judged`() {
        val waySection =
            LaneTopologyWaySection(
                id = "way/3",
                geometry = listOf(RoadCoordinate(48.999, 9.0), RoadCoordinate(49.0, 9.0)),
                ref = "A 8",
                lanes = "2",
                lanesForward = null,
                turnLanes = "through|through",
                destinationLanes = null,
                destinationRefLanes = null,
                changeLanes = null,
            )
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(maneuver = GoogleManeuver.MERGE_RIGHT, exitNumber = null)),
                listOf(junctionFeature(exitNumber = null)),
                listOf(waySection),
            )
        val lanes =
            guidance.junction
                ?.laneGuidance
                ?.lanes
                .orEmpty()
        assertTrue(lanes.isNotEmpty())
        assertTrue(lanes.all { it.state == LaneState.MERGING })
    }

    @Test
    fun `a plain Bundesstrasse exit with no Autobahn reference renders yellow without an inset`() {
        val waySection =
            LaneTopologyWaySection(
                id = "way/4",
                geometry = listOf(RoadCoordinate(48.999, 9.0), RoadCoordinate(49.0, 9.0)),
                ref = "B 10",
                lanes = "1",
                lanesForward = null,
                turnLanes = "slight_right",
                destinationLanes = "Goeppingen",
                destinationRefLanes = null,
                changeLanes = null,
            )
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(maneuver = GoogleManeuver.OFF_RAMP_RIGHT, fullRoadName = "B 10", exitNumber = null)),
                listOf(junctionFeature(exitNumber = null, detail = "toward Goeppingen · OpenStreetMap")),
                listOf(waySection),
            )
        val panel = guidance.junction?.panels?.singleOrNull()
        assertEquals(SignboardType.DIRECTION_YELLOW, panel?.type)
        assertNull(panel?.insetRoadRef)
    }

    @Test
    fun `a step further ahead than the lookahead window yields no signboard`() {
        val guidance =
            SignboardGuidanceEngine.build(
                navInfo(step = step(), distanceMeters = 5_000),
                listOf(junctionFeature(distanceMeters = 5_000)),
                emptyList(),
            )
        assertEquals(SignboardGuidance.NONE, guidance)
    }
}
