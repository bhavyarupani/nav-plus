package com.roadpulse.auto.signage

import com.roadpulse.auto.driving.RouteCameraAnalyzer
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.ManeuverType
import com.roadpulse.auto.traffic.LaneTopologyWaySection
import com.roadpulse.auto.traffic.RoadInfrastructureType

/**
 * Combines the free-stack [GuidanceState] turn-by-turn feed with OSM signboard/lane enrichment
 * into a single [SignboardGuidance], gated through [SignboardFallbackLevel]. The route and
 * maneuver themselves are never altered - OSM only adds destination text and lane-state detail
 * alongside them, and only when it can be matched with confidence. See THIRD_PARTY_DATA.md,
 * "Google Maps Platform compliance for OSM-enriched navigation display".
 *
 * GraphHopper's `Instruction.sign` vocabulary (confirmed via `javap` against the real jar) has no
 * "off-ramp"/"exit"/"fork" concept at all, unlike Google Navigation SDK's (now removed)
 * `Maneuver.OFF_RAMP_RIGHT` etc. - so [build] gates primarily on whether an OSM
 * `motorway_junction` point has been matched onto the upcoming route (`RouteRoadFeatureGuidance`,
 * already independent of the routing engine), falling back to GraphHopper's own `KEEP_LEFT`/
 * `KEEP_RIGHT` fork signal ([ManeuverType.FORK_LEFT]/[ManeuverType.FORK_RIGHT]) when there's no
 * OSM match - which is honest about producing no rich panel data in that case, not an attempt to
 * replicate Google's per-lane data (GraphHopper has none).
 */
object SignboardGuidanceEngine {
    fun build(
        guidanceState: GuidanceState,
        upcomingRoadFeatures: List<UpcomingRouteRoadFeature>,
        laneTopologySections: List<LaneTopologyWaySection>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SignboardGuidance {
        if (guidanceState.isRerouting || guidanceState.hasArrived) return SignboardGuidance.NONE
        val step = guidanceState.currentStep ?: return SignboardGuidance.NONE
        val distance = guidanceState.distanceToNextManeuverMeters ?: return SignboardGuidance.NONE
        if (distance < 0 || distance > MAX_LOOKAHEAD_METERS) return SignboardGuidance.NONE

        val junctionFeature =
            upcomingRoadFeatures.firstOrNull { it.point.type == RoadInfrastructureType.MOTORWAY_JUNCTION }
        val isForkOrRamp = step.maneuver == ManeuverType.FORK_LEFT || step.maneuver == ManeuverType.FORK_RIGHT
        if (junctionFeature == null && !isForkOrRamp) {
            // No junction/merge decision at this step, and no GraphHopper fork signal either -
            // plain maneuver only. GraphHopper has no per-lane data of its own to fall back to,
            // so this is unconditionally STANDARD_MANEUVER rather than SIMPLE_LANE_ARROWS.
            return SignboardGuidance.NONE
        }

        val junctionDistanceAgrees =
            junctionFeature != null &&
                kotlin.math.abs(junctionFeature.distanceMeters - distance) <=
                maxOf(JUNCTION_DISTANCE_TOLERANCE_METERS, distance / 4.0)
        val useOsmJunction = junctionFeature != null && junctionDistanceAgrees

        val exitNumber = junctionFeature?.point?.exitNumber.takeIf { useOsmJunction }
        val matchedWaySection =
            if (useOsmJunction) nearestLaneWaySection(junctionFeature, laneTopologySections) else null

        val exitTokens = graphHopperExitDirectionTokens(step.maneuver)
        val laneGuidance =
            matchedWaySection?.let { section -> osmLaneGuidance(exitTokens, section, nowMillis) }

        val destination = junctionFeature?.point?.detail?.let(::extractTowardText)
        val roadRef = matchedWaySection?.ref ?: step.roadName?.let(::extractRoadRef)
        val panels =
            buildSignboardPanels(
                exitNumber = exitNumber,
                destination = if (useOsmJunction) destination else null,
                laneGuidance = laneGuidance,
                roadRef = roadRef,
            )

        val source = if (useOsmJunction) GuidanceDataSource.OPENSTREETMAP else GuidanceDataSource.GRAPHHOPPER
        val confidence =
            when {
                useOsmJunction && junctionFeature?.confidence == com.roadpulse.auto.driving.RouteMatchConfidence.HIGH ->
                    GuidanceConfidence.HIGH
                useOsmJunction -> GuidanceConfidence.MEDIUM
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
            junction = junction.takeIf { fallbackLevel != SignboardFallbackLevel.STANDARD_MANEUVER },
            fallbackLevel = fallbackLevel,
        )
    }

    /** GraphHopper only distinguishes fork direction (see this object's class doc), so a
     * non-fork maneuver at a matched OSM junction is treated as "through" rather than left/right. */
    private fun graphHopperExitDirectionTokens(maneuver: ManeuverType): Set<String> =
        when (maneuver) {
            ManeuverType.FORK_RIGHT, ManeuverType.TURN_RIGHT, ManeuverType.TURN_SLIGHT_RIGHT -> setOf("right", "slight_right")
            ManeuverType.FORK_LEFT, ManeuverType.TURN_LEFT, ManeuverType.TURN_SLIGHT_LEFT -> setOf("left", "slight_left")
            else -> setOf("through")
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

    /** Exit-direction tokens matching [LaneTopologyParser]'s `turn:lanes` vocabulary. */
    private fun osmLaneGuidance(
        exitTokens: Set<String>?,
        matchedWaySection: LaneTopologyWaySection,
        nowMillis: Long,
    ): LaneGuidance? {
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
