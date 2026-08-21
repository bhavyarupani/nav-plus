package com.navplus.core.navigation

import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class RoadCharacterAnalyzer @Inject constructor() {

    fun analyzeAhead(route: Route, fromStepIndex: Int): List<RoadCharacter> {
        if (fromStepIndex >= route.steps.size) return emptyList()

        // Group consecutive steps by detected road type, collapse into segments
        val segments = mutableListOf<RoadCharacter>()
        var segmentSteps = mutableListOf<RouteStep>()
        var currentType = classifyStep(route.steps[fromStepIndex])

        for (i in fromStepIndex until route.steps.size) {
            val step = route.steps[i]
            val type = classifyStep(step)
            if (type != currentType && segmentSteps.isNotEmpty()) {
                segments.add(buildCharacter(segmentSteps, currentType))
                segmentSteps = mutableListOf()
                currentType = type
            }
            segmentSteps.add(step)
            if (segments.size >= 5) break
        }
        if (segmentSteps.isNotEmpty()) {
            segments.add(buildCharacter(segmentSteps, currentType))
        }
        return segments
    }

    private fun buildCharacter(steps: List<RouteStep>, type: RoadType): RoadCharacter {
        val totalDist = steps.sumOf { it.distanceMeters }
        val turnsPerKm = if (totalDist > 0) steps.count { it.maneuver.isTurn() } / (totalDist / 1000.0) else 0.0
        val complexityScore = (turnsPerKm / 4.0).toFloat().coerceIn(0f, 1f)
        val sharpTurns = steps.count { it.maneuver == Maneuver.TURN_SHARP_LEFT || it.maneuver == Maneuver.TURN_SHARP_RIGHT }
        val isMountain = type == RoadType.MOUNTAIN
        val roadName = steps.firstNotNullOfOrNull { it.streetName } ?: type.name.lowercase().replaceFirstChar { it.uppercase() }
        val description = buildDescription(type, totalDist, sharpTurns, complexityScore)

        return RoadCharacter(
            roadName = roadName,
            type = type,
            distanceMeters = totalDist,
            complexityScore = complexityScore,
            hairpinCount = sharpTurns,
            isSteep = isMountain && sharpTurns > 3,
            isNarrow = isMountain,
            description = description,
        )
    }

    private fun buildDescription(type: RoadType, distMeters: Double, hairpins: Int, complexity: Float): String {
        val parts = mutableListOf<String>()
        parts.add(type.label())
        if (hairpins > 0) parts.add("$hairpins hairpins")
        if (complexity > 0.7f) parts.add("winding")
        val distKm = (distMeters / 1000.0).let { if (it < 1) "${distMeters.roundToInt()} m" else "${"%.0f".format(it)} km" }
        parts.add(distKm)
        return parts.joinToString(" · ")
    }

    private fun classifyStep(step: RouteStep): RoadType {
        val name = (step.streetName ?: "").uppercase()
        val text = "${step.streetName.orEmpty()} ${step.instruction}".uppercase()
        val limit = step.speedLimitKph
        return when {
            text.contains("SCHOOL") || text.contains("SCHULE") || text.contains("KINDERGARTEN") -> RoadType.SCHOOL_ZONE
            text.contains("TRAFFIC CALMING") || text.contains("VERKEHRSBERUHIGT") -> RoadType.TRAFFIC_CALMING
            text.contains("LÄRMSCHUTZ") || text.contains("LAERMSCHUTZ") || text.contains("NOISE PROTECTION") -> RoadType.NOISE_PROTECTION
            limit != null && limit <= 30 -> RoadType.RESIDENTIAL
            step.maneuver == Maneuver.TUNNEL || text.contains("TUNNEL") -> RoadType.TUNNEL
            // Motorway: Autobahn A1, A8; French autoroute A7; UK M1
            name.matches(Regex("[AM]\\d+.*")) || name.contains("AUTOBAHN") || name.contains("MOTORWAY") -> RoadType.MOTORWAY
            // National roads B, RN, N
            name.matches(Regex("[BN]\\d+.*")) || name.contains("NATIONAL") -> RoadType.DUAL_CARRIAGEWAY
            // Ferry
            step.maneuver == Maneuver.FERRY || name.contains("FERRY") || name.contains("FÄHRE") -> RoadType.FERRY
            text.contains("RESIDENTIAL") || text.contains("WOHNGEBIET") || text.contains("WOHNSTRASSE") || text.contains("WOHNSTRAẞE") -> RoadType.RESIDENTIAL
            // Mountain heuristic: lots of sharp turns
            step.maneuver == Maneuver.TURN_SHARP_LEFT || step.maneuver == Maneuver.TURN_SHARP_RIGHT -> RoadType.MOUNTAIN
            // Urban
            step.distanceMeters < 300 -> RoadType.URBAN
            else -> RoadType.PRIMARY
        }
    }

    private fun RoadType.label(): String = when (this) {
        RoadType.MOTORWAY         -> "Easy motorway"
        RoadType.DUAL_CARRIAGEWAY -> "Dual carriageway"
        RoadType.PRIMARY          -> "Main road"
        RoadType.MOUNTAIN         -> "Mountain road"
        RoadType.RURAL            -> "Country road"
        RoadType.URBAN            -> "Town"
        RoadType.RESIDENTIAL      -> "Residential / 30 zone"
        RoadType.TRAFFIC_CALMING  -> "Traffic calming"
        RoadType.SCHOOL_ZONE      -> "School area"
        RoadType.NOISE_PROTECTION -> "Noise protection"
        RoadType.TUNNEL           -> "Tunnel"
        RoadType.FERRY            -> "Ferry crossing"
    }

    private fun Maneuver.isTurn(): Boolean = when (this) {
        Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT,
        Maneuver.TURN_SHARP_LEFT, Maneuver.TURN_SHARP_RIGHT,
        Maneuver.TURN_SLIGHT_LEFT, Maneuver.TURN_SLIGHT_RIGHT,
        Maneuver.U_TURN -> true
        else -> false
    }
}
