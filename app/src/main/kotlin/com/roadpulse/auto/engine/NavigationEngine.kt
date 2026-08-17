package com.roadpulse.auto.engine

import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CompletableFuture

/**
 * Provider-agnostic seams for map rendering, routing, search, and turn-by-turn guidance.
 *
 * These exist so the free-stack migration (MapLibre + GraphHopper + an offline search index) can
 * be built and tested independently of the working Google Navigation SDK path, and swapped in
 * once it reaches parity - see ZERO_COST_ARCHITECTURE.md for the decision record and migration
 * plan. Nothing in the app is wired to these yet; introducing them is step 3 of that plan.
 */

data class RoutePoint(
    val coordinate: RoadCoordinate,
    val title: String? = null,
)

data class Route(
    val id: String,
    val geometry: List<RoadCoordinate>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val isAlternative: Boolean = false,
    val steps: List<ManeuverStep> = emptyList(),
)

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    TURN_SLIGHT_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT,
    TURN_SHARP_RIGHT,
    U_TURN,
    ON_RAMP,
    OFF_RAMP,
    FORK_LEFT,
    FORK_RIGHT,
    MERGE,
    ROUNDABOUT,
    DESTINATION,
    UNKNOWN,
}

/** Mirrors Google's per-lane recommendation model closely enough for existing UI (the signboard/
 * lane panel) to consume either source interchangeably. Populated only when the routing engine
 * can supply it - GraphHopper 7.0's simple API doesn't, so this stays null there; the app's
 * primary lane-guidance source is the OSM-derived `SignboardGuidanceEngine`, not this field. */
enum class LaneDirection {
    LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    STRAIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    U_TURN,
    UNKNOWN,
}

data class LaneInfo(
    val directions: List<LaneDirection>,
    val isRecommended: Boolean,
)

data class ManeuverStep(
    val maneuver: ManeuverType,
    val instructionText: String,
    val roadName: String?,
    val exitNumber: String?,
    val distanceMeters: Int,
    val lanes: List<LaneInfo>? = null,
)

data class GuidanceState(
    val currentStep: ManeuverStep?,
    val nextStep: ManeuverStep?,
    val distanceToDestinationMeters: Int?,
    val etaEpochSeconds: Long?,
    val isRerouting: Boolean,
    val hasArrived: Boolean = false,
)

/** Provider-agnostic route-request outcome, mirroring Google's `Navigator.RouteStatus` closely
 * enough to drive the same "Route unavailable: ..." UI text. */
enum class RouteRequestStatus {
    OK,
    NO_ROUTE_FOUND,
    NETWORK_ERROR,
    LOCATION_UNAVAILABLE,
    CANCELED,
    UNKNOWN_ERROR,
}

/** Thrown by [RoutingEngine] implementations so callers can recover a [RouteRequestStatus]
 * from a failed [CompletableFuture] via `(exception as? RouteCalculationException)?.status`. */
class RouteCalculationException(
    val status: RouteRequestStatus,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Calculates and maintains a route. Implementations may run entirely on-device. Uses
 * CompletableFuture, matching this project's existing convention for async work (no coroutines
 * dependency elsewhere in the codebase). */
interface RoutingEngine {
    fun calculateRoute(
        origin: RoadCoordinate,
        destination: RoadCoordinate,
        waypoints: List<RoadCoordinate> = emptyList(),
        avoidHighways: Boolean = false,
    ): CompletableFuture<List<Route>>

    fun recalculateRoute(
        currentLocation: RoadCoordinate,
        destination: RoadCoordinate,
    ): CompletableFuture<Route>
}

/** Turn-by-turn guidance over an active [Route]. Mirrors the shape of Google's NavInfo/StepInfo
 * closely enough that both a Google-backed and a free-stack implementation can produce it. */
interface GuidanceEngine {
    fun startGuidance(route: Route)

    fun stopGuidance()

    fun addListener(listener: (GuidanceState) -> Unit)

    fun removeListener(listener: (GuidanceState) -> Unit)

    fun onLocationUpdate(
        coordinate: RoadCoordinate,
        speedKph: Float?,
        bearingDegrees: Float?,
    )
}

data class SearchResult(
    val title: String,
    val subtitle: String?,
    val coordinate: RoadCoordinate,
)

/** Destination search / geocoding, independent of whether it's backed by a hosted service or a
 * fully offline on-device index. */
interface SearchEngine {
    fun search(
        query: String,
        nearCoordinate: RoadCoordinate? = null,
    ): CompletableFuture<List<SearchResult>>
}

data class MapRegion(
    val id: String,
    val displayName: String,
    val downloadSizeBytes: Long?,
    val installedSizeBytes: Long?,
    val isDownloaded: Boolean,
)

/** Downloadable regional map/routing-graph package management (Geofabrik-derived packages, not
 * a live tile service - see ZERO_COST_ARCHITECTURE.md, "Map-package strategy"). */
interface MapDataProvider {
    fun availableRegions(): CompletableFuture<List<MapRegion>>

    fun downloadRegion(regionId: String): CompletableFuture<Unit>

    fun deleteRegion(regionId: String): CompletableFuture<Unit>
}
