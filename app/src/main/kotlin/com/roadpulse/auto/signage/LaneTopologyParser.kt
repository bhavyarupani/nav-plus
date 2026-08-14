package com.roadpulse.auto.signage

/**
 * Turns raw OSM `turn:lanes` / `destination:lanes` / `lanes` tag text into [LaneGuidance].
 * Pure and side-effect free so every branch is independently testable (see
 * `LaneTopologyParserTest`). Never invents a lane count, destination, or state that isn't
 * directly present in the tag text - unparsable or inconsistent input returns `null` /
 * [LaneState.UNKNOWN] rather than a guess, per the fallback rules in the master spec.
 */
object LaneTopologyParser {
    /** `"through|through;slight_right|slight_right"` -> per-lane sets of turn tokens, left to
     * right, matching OSM's `:lanes` numbering convention. */
    fun parseTurnLanes(value: String?): List<Set<String>> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split("|").map { lane ->
            lane
                .split(";")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }
    }

    /** `"|||Esslingen"` -> one entry per lane, blank segments as null. */
    fun parsePipeList(value: String?): List<String?> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split("|").map { it.trim().ifBlank { null } }
    }

    fun laneShapesFor(turnTokens: Set<String>): List<LaneShape> {
        val shapes =
            turnTokens.mapNotNull { token ->
                when (token) {
                    "through" -> LaneShape.STRAIGHT
                    "left" -> LaneShape.NORMAL_LEFT
                    "right" -> LaneShape.NORMAL_RIGHT
                    "slight_left" -> LaneShape.SLIGHT_LEFT
                    "slight_right" -> LaneShape.SLIGHT_RIGHT
                    "sharp_left" -> LaneShape.SHARP_LEFT
                    "sharp_right" -> LaneShape.SHARP_RIGHT
                    "reverse" -> LaneShape.U_TURN_LEFT
                    "merge_to_left" -> LaneShape.SLIGHT_LEFT
                    "merge_to_right" -> LaneShape.SLIGHT_RIGHT
                    else -> null
                }
            }
        return shapes.ifEmpty { listOf(LaneShape.UNKNOWN) }
    }

    /**
     * Resolves per-lane state for the upcoming junction.
     *
     * [exitTurnTokens] is the set of `turn:lanes` tokens that correspond to following the
     * active route (e.g. `{"slight_right"}` for a typical right-hand exit, `{"through"}` when
     * the route continues straight through the junction). Pass `null` when the route's own
     * direction at this junction is not confidently known - every lane then resolves to
     * [LaneState.UNKNOWN] instead of guessing which lane matters.
     *
     * Returns `null` when `turn:lanes` is missing/unparsable, or when the `lanes` tag disagrees
     * with the number of `turn:lanes` segments (internally inconsistent OSM data - the spec's
     * "if lane count is uncertain, do not render a detailed sign" rule).
     */
    fun resolve(
        turnLanes: String?,
        destinationLanes: String?,
        destinationRefLanes: String?,
        laneCount: Int?,
        exitTurnTokens: Set<String>?,
        source: GuidanceDataSource,
        confidence: GuidanceConfidence,
        nowMillis: Long,
    ): LaneGuidance? {
        val parsedTurns = parseTurnLanes(turnLanes)
        if (parsedTurns.isEmpty()) return null
        if (laneCount != null && laneCount != parsedTurns.size) return null

        val destinations = parsePipeList(destinationLanes)
        val refs = parsePipeList(destinationRefLanes)
        val lanes =
            parsedTurns.mapIndexed { index, tokens ->
                val destinationText =
                    listOfNotNull(refs.getOrNull(index), destinations.getOrNull(index))
                        .joinToString(" ")
                        .ifBlank { null }
                LaneGuidanceLane(
                    shapes = laneShapesFor(tokens),
                    state = laneState(tokens, exitTurnTokens),
                    destination = destinationText?.let { SignpostedDestination(it, setOf(index)) },
                )
            }
        return LaneGuidance(lanes, source, confidence, nowMillis)
    }

    private fun laneState(
        tokens: Set<String>,
        exitTurnTokens: Set<String>?,
    ): LaneState {
        if (exitTurnTokens == null || tokens.isEmpty() || tokens == setOf("none")) {
            return LaneState.UNKNOWN
        }
        val allTokensMatchRoute = tokens.all { it in exitTurnTokens }
        val anyTokenMatchesRoute = tokens.any { it in exitTurnTokens }
        return when {
            allTokensMatchRoute && tokens.size == 1 && "through" !in tokens -> LaneState.EXIT_ONLY
            allTokensMatchRoute -> LaneState.RECOMMENDED
            anyTokenMatchesRoute -> LaneState.PERMITTED
            else -> LaneState.NOT_RECOMMENDED
        }
    }

    /**
     * Compares the mainline `lanes` count immediately before and at the segment approaching a
     * junction (in route order) to flag an added or ending lane. Returns `null` when either
     * count is unknown or they already agree - this only ever flags a *change*, never invents
     * one. Only a difference of exactly one lane is classified: larger jumps usually indicate a
     * genuinely different road (e.g. a different route was matched) rather than a single
     * acceleration/deceleration lane, so those are left unclassified rather than guessed.
     */
    fun laneCountChange(
        previousLaneCount: Int?,
        currentLaneCount: Int?,
    ): LaneCountChange? {
        if (previousLaneCount == null || currentLaneCount == null) return null
        return when (currentLaneCount - previousLaneCount) {
            1 -> LaneCountChange.ADDED
            -1 -> LaneCountChange.ENDING
            else -> null
        }
    }

    /**
     * Applies a detected [LaneCountChange] to the outermost lane. German motorway
     * acceleration/deceleration lanes are conventionally added and dropped on the right in
     * right-hand traffic; this is a documented simplifying assumption (see THIRD_PARTY_DATA.md
     * / known limitations), not a claim verified per-junction, so it only overrides a lane that
     * was otherwise going to be shown as NOT_RECOMMENDED or UNKNOWN - it never overrides a lane
     * the route data already positively recommends.
     */
    fun applyLaneCountChange(
        lanes: List<LaneGuidanceLane>,
        change: LaneCountChange?,
    ): List<LaneGuidanceLane> {
        if (change == null || lanes.isEmpty()) return lanes
        val rightmostIndex = lanes.lastIndex
        val target = lanes[rightmostIndex]
        if (target.state != LaneState.NOT_RECOMMENDED && target.state != LaneState.UNKNOWN) return lanes
        val newState = if (change == LaneCountChange.ADDED) LaneState.ADDED else LaneState.ENDING
        return lanes.toMutableList().apply { this[rightmostIndex] = target.copy(state = newState) }
    }
}

enum class LaneCountChange { ADDED, ENDING }
