package com.roadpulse.auto.signage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTopologyParserTest {
    @Test
    fun `parses pipe separated turn lanes left to right with multi-value lanes`() {
        val parsed = LaneTopologyParser.parseTurnLanes("through|through;slight_right|slight_right")
        assertEquals(3, parsed.size)
        assertEquals(setOf("through"), parsed[0])
        assertEquals(setOf("through", "slight_right"), parsed[1])
        assertEquals(setOf("slight_right"), parsed[2])
    }

    @Test
    fun `blank turn lanes value parses as empty`() {
        assertTrue(LaneTopologyParser.parseTurnLanes(null).isEmpty())
        assertTrue(LaneTopologyParser.parseTurnLanes("").isEmpty())
    }

    @Test
    fun `parses destination lanes preserving blank lanes as null`() {
        val parsed = LaneTopologyParser.parsePipeList("|||Esslingen")
        assertEquals(listOf(null, null, null, "Esslingen"), parsed)
    }

    @Test
    fun `maps recognised turn tokens to shapes and unknown tokens fall back to UNKNOWN`() {
        assertEquals(listOf(LaneShape.STRAIGHT), LaneTopologyParser.laneShapesFor(setOf("through")))
        assertEquals(
            setOf(LaneShape.STRAIGHT, LaneShape.SLIGHT_RIGHT),
            LaneTopologyParser.laneShapesFor(setOf("through", "slight_right")).toSet(),
        )
        assertEquals(listOf(LaneShape.UNKNOWN), LaneTopologyParser.laneShapesFor(setOf("bogus_token")))
        assertEquals(listOf(LaneShape.UNKNOWN), LaneTopologyParser.laneShapesFor(emptySet()))
    }

    @Test
    fun `resolves recommended and exit-only lanes for a right-hand exit`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = "through|through|slight_right",
                destinationLanes = "||Esslingen",
                destinationRefLanes = null,
                laneCount = 3,
                exitTurnTokens = setOf("right", "slight_right", "sharp_right"),
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        requireNotNull(guidance)
        assertEquals(3, guidance.lanes.size)
        assertEquals(LaneState.NOT_RECOMMENDED, guidance.lanes[0].state)
        assertEquals(LaneState.NOT_RECOMMENDED, guidance.lanes[1].state)
        assertEquals(LaneState.EXIT_ONLY, guidance.lanes[2].state)
        assertEquals("Esslingen", guidance.lanes[2].destination?.text)
        assertEquals(setOf(2), guidance.lanes[2].destination?.laneIndices)
    }

    @Test
    fun `a lane with both through and the exit token is permitted, not exit-only`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = "through|through;slight_right",
                destinationLanes = null,
                destinationRefLanes = null,
                laneCount = 2,
                exitTurnTokens = setOf("slight_right"),
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        requireNotNull(guidance)
        assertEquals(LaneState.NOT_RECOMMENDED, guidance.lanes[0].state)
        assertEquals(LaneState.PERMITTED, guidance.lanes[1].state)
    }

    @Test
    fun `continuing straight recommends the through lane without marking it exit-only`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = "through|through|slight_right",
                destinationLanes = null,
                destinationRefLanes = null,
                laneCount = 3,
                exitTurnTokens = setOf("through"),
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        requireNotNull(guidance)
        assertEquals(LaneState.RECOMMENDED, guidance.lanes[0].state)
        assertEquals(LaneState.RECOMMENDED, guidance.lanes[1].state)
        assertEquals(LaneState.NOT_RECOMMENDED, guidance.lanes[2].state)
    }

    @Test
    fun `unknown route direction resolves every lane to UNKNOWN rather than guessing`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = "through|slight_right",
                destinationLanes = null,
                destinationRefLanes = null,
                laneCount = 2,
                exitTurnTokens = null,
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        requireNotNull(guidance)
        assertTrue(guidance.lanes.all { it.state == LaneState.UNKNOWN })
    }

    @Test
    fun `a lanes tag count that disagrees with turn lanes segmentation is rejected as uncertain`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = "through|through|slight_right",
                destinationLanes = null,
                destinationRefLanes = null,
                laneCount = 4,
                exitTurnTokens = setOf("through"),
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        assertNull(guidance)
    }

    @Test
    fun `missing turn lanes tag resolves to no guidance rather than an empty guess`() {
        val guidance =
            LaneTopologyParser.resolve(
                turnLanes = null,
                destinationLanes = "Esslingen",
                destinationRefLanes = null,
                laneCount = 1,
                exitTurnTokens = setOf("through"),
                source = GuidanceDataSource.OPENSTREETMAP,
                confidence = GuidanceConfidence.MEDIUM,
                nowMillis = 1_000L,
            )
        assertNull(guidance)
    }

    @Test
    fun `lane count change detects an added or ending lane only on a single-lane difference`() {
        assertEquals(LaneCountChange.ADDED, LaneTopologyParser.laneCountChange(2, 3))
        assertEquals(LaneCountChange.ENDING, LaneTopologyParser.laneCountChange(3, 2))
        assertNull(LaneTopologyParser.laneCountChange(2, 2))
        assertNull(LaneTopologyParser.laneCountChange(2, 5))
        assertNull(LaneTopologyParser.laneCountChange(null, 3))
    }

    @Test
    fun `applying a lane count change only overrides the outermost non-recommended lane`() {
        val lanes =
            listOf(
                LaneGuidanceLane(listOf(LaneShape.STRAIGHT), LaneState.RECOMMENDED),
                LaneGuidanceLane(listOf(LaneShape.UNKNOWN), LaneState.NOT_RECOMMENDED),
            )
        val result = LaneTopologyParser.applyLaneCountChange(lanes, LaneCountChange.ADDED)
        assertEquals(LaneState.RECOMMENDED, result[0].state)
        assertEquals(LaneState.ADDED, result[1].state)
    }

    @Test
    fun `applying a lane count change never overrides an already-recommended lane`() {
        val lanes = listOf(LaneGuidanceLane(listOf(LaneShape.STRAIGHT), LaneState.RECOMMENDED))
        val result = LaneTopologyParser.applyLaneCountChange(lanes, LaneCountChange.ENDING)
        assertEquals(LaneState.RECOMMENDED, result[0].state)
    }
}
