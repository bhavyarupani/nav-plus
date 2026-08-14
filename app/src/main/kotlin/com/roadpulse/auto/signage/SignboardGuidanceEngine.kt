package com.roadpulse.auto.signage

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.roadpulse.auto.driving.RouteCameraAnalyzer
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.traffic.LaneTopologyWaySection
import com.roadpulse.auto.traffic.RoadInfrastructureType
import java.util.Locale
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection.LaneShape as GoogleLaneShape
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver

/**
 * Combines Google's authoritative turn-by-turn feed with OSM signboard/lane enrichment into a
 * single [SignboardGuidance], gated through [SignboardFallbackLevel]. Google's route and
 * maneuver are never altered - OSM only adds destination text and lane-state detail alongside
 * them, and only when it can be matched with confidence. See THIRD_PARTY_DATA.md, "Google Maps
 * Platform compliance for OSM-enriched navigation display".
 */
object SignboardGuidanceEngine {
    fun build(
        navInfo: NavInfo,
        upcomingRoadFeatures: List<UpcomingRouteRoadFeature>,
        laneTopologySections: List<LaneTopologyWaySection>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SignboardGuidance {
        if (navInfo.navState != NavState.ENROUTE) return SignboardGuidance.NONE
        if (navInfo.routeChanged) return SignboardGuidance.NONE
        val step = navInfo.currentStep ?: return SignboardGuidance.NONE
        val distance = navInfo.distanceToCurrentStepMeters ?: return SignboardGuidance.NONE
        if (distance < 0 || distance > MAX_LOOKAHEAD_METERS) return SignboardGuidance.NONE

        val isExit = step.maneuver in EXIT_MANEUVERS
        val isMerge = step.maneuver in MERGE_MANEUVERS
        if (!isExit && !isMerge) {
            // No junction/merge decision at this step: only Google's own lane data (if any)
            // is in scope, no signboard.
            val googleLanes = googleLaneGuidance(step, nowMillis)
            return SignboardGuidance(
                junction =
                    googleLanes?.let {
                        JunctionGuidance(
                            panels = emptyList(),
                            laneGuidance = it,
                            distanceMetersToJunction = distance,
                            source = GuidanceDataSource.GOOGLE_NAVIGATION_SDK,
                            confidence = GuidanceConfidence.HIGH,
                            timestampMillis = nowMillis,
                        )
                    },
                fallbackLevel =
                    if (googleLanes !=
                        null
                    ) {
                        SignboardFallbackLevel.SIMPLE_LANE_ARROWS
                    } else {
                        SignboardFallbackLevel.STANDARD_MANEUVER
                    },
            )
        }

        val junctionFeature =
            upcomingRoadFeatures.firstOrNull { it.point.type == RoadInfrastructureType.MOTORWAY_JUNCTION }

        val googleExitNumber = step.exitNumber?.takeIf(String::isNotBlank)
        val osmExitNumber = junctionFeature?.point?.exitNumber
        val exitNumberConflict =
            googleExitNumber != null &&
                osmExitNumber != null &&
                normalizeRef(googleExitNumber) != normalizeRef(osmExitNumber)

        val junctionDistanceAgrees =
            junctionFeature != null &&
                kotlin.math.abs(junctionFeature.distanceMeters - distance) <=
                maxOf(JUNCTION_DISTANCE_TOLERANCE_METERS, distance / 4.0)

        val useOsmJunction = junctionFeature != null && junctionDistanceAgrees && !exitNumberConflict

        val exitNumber = googleExitNumber ?: osmExitNumber.takeIf { useOsmJunction }

        val matchedWaySection =
            if (useOsmJunction) nearestLaneWaySection(junctionFeature, laneTopologySections) else null

        val googleLanes = googleLaneGuidance(step, nowMillis)
        val osmLanes =
            if (useOsmJunction && matchedWaySection != null) {
                osmLaneGuidance(step, matchedWaySection, isExit, nowMillis)
            } else {
                null
            }

        val combinedLanes =
            when {
                osmLanes != null && googleLanes != null && osmLanes.lanes.size == googleLanes.lanes.size ->
                    osmLanes.copy(source = GuidanceDataSource.BLENDED)
                osmLanes != null && googleLanes == null -> osmLanes
                else -> googleLanes
            }
        // A merge maneuver's lanes are joining traffic, not choosing among route options - every
        // known lane is MERGING rather than individually recommended/not-recommended.
        val laneGuidance =
            if (isMerge && combinedLanes != null) {
                combinedLanes.copy(lanes = combinedLanes.lanes.map { it.copy(state = LaneState.MERGING) })
            } else {
                combinedLanes
            }

        val destination = junctionFeature?.point?.detail?.let(::extractTowardText)
        val roadRef = matchedWaySection?.ref ?: step.fullRoadName?.let(::extractRoadRef)
        val panels =
            buildSignboardPanels(
                exitNumber = exitNumber,
                destination = if (useOsmJunction) destination else null,
                laneGuidance = laneGuidance,
                roadRef = roadRef,
            )

        val source =
            when {
                useOsmJunction && googleLanes != null -> GuidanceDataSource.BLENDED
                useOsmJunction -> GuidanceDataSource.OPENSTREETMAP
                else -> GuidanceDataSource.GOOGLE_NAVIGATION_SDK
            }
        val confidence =
            when {
                exitNumberConflict -> GuidanceConfidence.LOW
                useOsmJunction && junctionFeature?.confidence == com.roadpulse.auto.driving.RouteMatchConfidence.HIGH ->
                    GuidanceConfidence.HIGH
                useOsmJunction -> GuidanceConfidence.MEDIUM
                exitNumber != null -> GuidanceConfidence.MEDIUM
                else -> GuidanceConfidence.LOW
            }

        val junction =
            JunctionGuidance(
                panels = panels,
                laneGuidance = laneGuidance,
                distanceMetersToJunction = distance,
                source = source,
                confidence = confidence,
                timestampMillis = nowMillis,
            )

        val fallbackLevel = SignboardFallbackEngine.select(junction)
        return SignboardGuidance(
            junction =
                junction.takeIf {
                    fallbackLevel != SignboardFallbackLevel.STANDARD_MANEUVER
                },
            fallbackLevel = fallbackLevel,
        )
    }

    private fun buildSignboardPanels(
        exitNumber: String?,
        destination: String?,
        laneGuidance: LaneGuidance?,
        roadRef: String?,
    ): List<SignboardPanel> {
        if (exitNumber == null && destination == null) return emptyList()
        val laneDestinations =
            laneGuidance?.lanes.orEmpty().mapIndexedNotNull { index, lane ->
                lane.destination?.let { SignpostedDestination(it.text, setOf(index)) }
            }
        val generalDestinations =
            listOfNotNull(destination?.let { SignpostedDestination(it) })
        val destinations = (laneDestinations + generalDestinations).distinctBy { it.text to it.laneIndices }
        val baseType = classifySignboardType(roadRef)
        val insetRef =
            destinations
                .firstNotNullOfOrNull { extractRoadRef(it.text) }
                ?.takeIf { it != roadRef }
        val type = if (baseType == SignboardType.DIRECTION_YELLOW && insetRef != null) SignboardType.MIXED else baseType
        return listOf(
            SignboardPanel(
                type = type,
                roadRef = roadRef,
                exitNumber = exitNumber,
                destinations = destinations,
                insetRoadRef = insetRef.takeIf { type == SignboardType.MIXED },
            ),
        )
    }

    /** Never guesses a colour: a ref starting "A" is Autobahn (blue), anything else with a real
     * ref is a general direction sign (yellow); an unknown ref renders [SignboardType.NONE]. */
    private fun classifySignboardType(roadRef: String?): SignboardType =
        when {
            roadRef == null -> SignboardType.NONE
            roadRef.trim().startsWith("A", ignoreCase = true) -> SignboardType.AUTOBAHN_BLUE
            else -> SignboardType.DIRECTION_YELLOW
        }

    private fun extractRoadRef(roadName: String): String? = ROAD_REF_PATTERN.find(roadName)?.value

    private fun googleLaneGuidance(
        step: StepInfo,
        nowMillis: Long,
    ): LaneGuidance? {
        val lanes = step.lanes.orEmpty()
        if (lanes.isEmpty()) return null
        val guidanceLanes =
            lanes.map { lane ->
                val directions = lane.laneDirections()
                val shapes = directions.map { it.laneShape().toLaneShape() }.ifEmpty { listOf(LaneShape.UNKNOWN) }
                val state =
                    when {
                        directions.any { it.isRecommended == true } -> LaneState.RECOMMENDED
                        directions.all { it.isRecommended == false } && directions.isNotEmpty() -> LaneState.NOT_RECOMMENDED
                        else -> LaneState.UNKNOWN
                    }
                LaneGuidanceLane(shapes = shapes, state = state)
            }
        return LaneGuidance(guidanceLanes, GuidanceDataSource.GOOGLE_NAVIGATION_SDK, GuidanceConfidence.HIGH, nowMillis)
    }

    private fun osmLaneGuidance(
        step: StepInfo,
        matchedWaySection: LaneTopologyWaySection,
        isExit: Boolean,
        nowMillis: Long,
    ): LaneGuidance? {
        val exitTokens =
            when {
                step.maneuver in RIGHT_EXIT_MANEUVERS -> setOf("right", "slight_right", "sharp_right")
                step.maneuver in LEFT_EXIT_MANEUVERS -> setOf("left", "slight_left", "sharp_left")
                isExit -> null
                else -> setOf("through")
            }

        val laneCount = matchedWaySection.lanes?.trim()?.toIntOrNull()
        return LaneTopologyParser.resolve(
            turnLanes = matchedWaySection.turnLanes,
            destinationLanes = matchedWaySection.destinationLanes,
            destinationRefLanes = matchedWaySection.destinationRefLanes,
            laneCount = laneCount,
            exitTurnTokens = exitTokens,
            source = GuidanceDataSource.OPENSTREETMAP,
            confidence = GuidanceConfidence.MEDIUM,
            nowMillis = nowMillis,
        )
    }

    private fun nearestLaneWaySection(
        junctionFeature: UpcomingRouteRoadFeature,
        laneTopologySections: List<LaneTopologyWaySection>,
    ): LaneTopologyWaySection? =
        laneTopologySections
            .map { section -> section to nearestDistanceMeters(section, junctionFeature) }
            .filter { (_, dist) -> dist <= LANE_WAY_ASSOCIATION_METERS }
            .minByOrNull { (_, dist) -> dist }
            ?.first

    private fun nearestDistanceMeters(
        section: LaneTopologyWaySection,
        junctionFeature: UpcomingRouteRoadFeature,
    ): Double =
        section.geometry.minOf { coordinate ->
            RouteCameraAnalyzer.distanceMeters(coordinate, junctionFeature.point.coordinate)
        }

    private fun extractTowardText(detail: String): String? =
        detail.split(" · ").firstOrNull { it.startsWith("toward ") }?.removePrefix("toward ")

    private fun normalizeRef(raw: String): String = raw.uppercase(Locale.ROOT).replace(" ", "").replace("-", "")

    private fun Int.toLaneShape(): LaneShape =
        when (this) {
            GoogleLaneShape.STRAIGHT -> LaneShape.STRAIGHT
            GoogleLaneShape.SLIGHT_LEFT -> LaneShape.SLIGHT_LEFT
            GoogleLaneShape.SLIGHT_RIGHT -> LaneShape.SLIGHT_RIGHT
            GoogleLaneShape.NORMAL_LEFT -> LaneShape.NORMAL_LEFT
            GoogleLaneShape.NORMAL_RIGHT -> LaneShape.NORMAL_RIGHT
            GoogleLaneShape.SHARP_LEFT -> LaneShape.SHARP_LEFT
            GoogleLaneShape.SHARP_RIGHT -> LaneShape.SHARP_RIGHT
            GoogleLaneShape.U_TURN_LEFT -> LaneShape.U_TURN_LEFT
            GoogleLaneShape.U_TURN_RIGHT -> LaneShape.U_TURN_RIGHT
            else -> LaneShape.UNKNOWN
        }

    private val RIGHT_EXIT_MANEUVERS =
        setOf(
            GoogleManeuver.OFF_RAMP_RIGHT,
            GoogleManeuver.OFF_RAMP_KEEP_RIGHT,
            GoogleManeuver.OFF_RAMP_SLIGHT_RIGHT,
            GoogleManeuver.OFF_RAMP_SHARP_RIGHT,
            GoogleManeuver.FORK_RIGHT,
        )
    private val LEFT_EXIT_MANEUVERS =
        setOf(
            GoogleManeuver.OFF_RAMP_LEFT,
            GoogleManeuver.OFF_RAMP_KEEP_LEFT,
            GoogleManeuver.OFF_RAMP_SLIGHT_LEFT,
            GoogleManeuver.OFF_RAMP_SHARP_LEFT,
            GoogleManeuver.FORK_LEFT,
        )
    private val EXIT_MANEUVERS =
        RIGHT_EXIT_MANEUVERS + LEFT_EXIT_MANEUVERS +
            setOf(
                GoogleManeuver.OFF_RAMP_UNSPECIFIED,
                GoogleManeuver.OFF_RAMP_U_TURN_CLOCKWISE,
                GoogleManeuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            )
    private val MERGE_MANEUVERS = setOf(GoogleManeuver.MERGE_LEFT, GoogleManeuver.MERGE_RIGHT, GoogleManeuver.MERGE_UNSPECIFIED)

    private const val MAX_LOOKAHEAD_METERS = 2_000
    private const val JUNCTION_DISTANCE_TOLERANCE_METERS = 300.0
    private const val LANE_WAY_ASSOCIATION_METERS = 350.0
    private val ROAD_REF_PATTERN = Regex("""\b[AB]\s?\d+\b""")
}

/** Implements the "full signboard -> junction view -> simple lane arrows -> standard maneuver"
 * fallback order: always the highest tier whose data is reliable enough to show. */
object SignboardFallbackEngine {
    fun select(junction: JunctionGuidance): SignboardFallbackLevel {
        val hasReliablePanels =
            junction.panels.isNotEmpty() &&
                junction.confidence != GuidanceConfidence.LOW &&
                junction.panels.any { panel -> panel.exitNumber != null || panel.destinations.isNotEmpty() }
        val hasReliableLaneCount = junction.laneGuidance?.laneCountKnown == true
        return when {
            hasReliablePanels && hasReliableLaneCount -> SignboardFallbackLevel.FULL_SIGNBOARD
            hasReliablePanels -> SignboardFallbackLevel.JUNCTION_VIEW
            hasReliableLaneCount -> SignboardFallbackLevel.SIMPLE_LANE_ARROWS
            else -> SignboardFallbackLevel.STANDARD_MANEUVER
        }
    }
}
