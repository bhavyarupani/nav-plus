package com.roadpulse.auto.stops

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import com.roadpulse.auto.driving.RouteCameraAnalyzer
import com.roadpulse.auto.quota.GoogleUsageGuard
import com.roadpulse.auto.quota.QuotaDecision
import com.roadpulse.auto.traffic.RoadCoordinate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Selects open stops either at the earliest useful route position or by lowest detour. */
class RouteStopOptimizer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = RouteStopPreferencesStore(appContext)
    private val usageGuard = GoogleUsageGuard(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setRoute(
        navigator: Navigator,
        finalDestination: Waypoint,
        routingOptions: RoutingOptions,
        onProgress: (String) -> Unit = {},
        onComplete: (RouteStopPlan) -> Unit,
    ) {
        val enabled = preferences.load()
        if (!enabled.hasEnabledStop) {
            navigator.setDestination(finalDestination, routingOptions).setOnResultListener { status ->
                onComplete(RouteStopPlan(status = status))
            }
            return
        }

        onProgress("Calculating the direct route before optimizing stops…")
        navigator.setDestination(finalDestination, routingOptions).setOnResultListener { baseStatus ->
            if (baseStatus != Navigator.RouteStatus.OK) {
                onComplete(RouteStopPlan(status = baseStatus))
                return@setOnResultListener
            }

            val routePoints = navigator.routeSegments.flatMap { it.latLngs }.deduplicated()
            val direct = navigator.timeAndDistanceList
            val directMeters = direct.sumOf { it.meters }
            val directSeconds = direct.sumOf { it.seconds }
            if (routePoints.size < 2 || directMeters <= 0) {
                onComplete(
                    RouteStopPlan(
                        status = Navigator.RouteStatus.OK,
                        note = "Direct route used because its route line was unavailable.",
                    ),
                )
                return@setOnResultListener
            }

            searchStops(
                routePoints = routePoints,
                directMeters = directMeters,
                directSeconds = directSeconds,
                enabled = enabled,
                onProgress = onProgress,
            ) { selections, note ->
                if (selections.isEmpty()) {
                    onComplete(RouteStopPlan(Navigator.RouteStatus.OK, note = note))
                    return@searchStops
                }

                val stopWaypoints =
                    selections.sortedBy { it.metersFromRouteOrigin }.mapNotNull {
                        runCatching {
                            Waypoint
                                .builder()
                                .setLatLng(it.coordinate.latitude, it.coordinate.longitude)
                                .setTitle(it.title)
                                .setVehicleStopover(true)
                                .build()
                        }.getOrNull()
                    }
                if (stopWaypoints.isEmpty()) {
                    onComplete(RouteStopPlan(Navigator.RouteStatus.OK, note = note))
                    return@searchStops
                }

                when (usageGuard.navigationDestinations.tryConsume()) {
                    is QuotaDecision.Blocked -> {
                        onComplete(
                            RouteStopPlan(
                                Navigator.RouteStatus.OK,
                                note = "Stops skipped because the monthly route limit is reached.",
                            ),
                        )
                    }
                    is QuotaDecision.Allowed -> {
                        onProgress("Building the route with ${stopWaypoints.size} optimized stop(s)…")
                        navigator
                            .setDestinations(
                                stopWaypoints + finalDestination,
                                routingOptions,
                            ).setOnResultListener { optimizedStatus ->
                                if (optimizedStatus == Navigator.RouteStatus.OK) {
                                    onComplete(
                                        RouteStopPlan(
                                            status = optimizedStatus,
                                            stops = selections.sortedBy { it.metersFromRouteOrigin },
                                            note = note,
                                        ),
                                    )
                                } else {
                                    // Preserve useful navigation even if an intermediate place cannot route.
                                    navigator
                                        .setDestination(finalDestination, routingOptions)
                                        .setOnResultListener { fallbackStatus ->
                                            onComplete(
                                                RouteStopPlan(
                                                    status = fallbackStatus,
                                                    note = "The optimized stop route was unavailable; using the direct route.",
                                                ),
                                            )
                                        }
                                }
                            }
                    }
                }
            }
        }
    }

    /**
     * Runs the free OpenStreetMap corridor search off the main thread: a single
     * Overpass query covers every enabled category, replacing what used to be one
     * paid Google Places "search along route" call per category.
     */
    private fun searchStops(
        routePoints: List<LatLng>,
        directMeters: Int,
        directSeconds: Int,
        enabled: RouteStopPreferences,
        onProgress: (String) -> Unit,
        onComplete: (List<OptimizedRouteStop>, String?) -> Unit,
    ) {
        val requests =
            buildList {
                if (enabled.supermarketMode != RouteStopMode.OFF) {
                    add(RouteStopCategory.SUPERMARKET to enabled.supermarketMode)
                }
                if (enabled.fuelMode != RouteStopMode.OFF) {
                    add(RouteStopCategory.FUEL to enabled.fuelMode)
                }
            }
        if (requests.isEmpty()) {
            onComplete(emptyList(), null)
            return
        }

        val geometry = routePoints.map { RoadCoordinate(it.latitude, it.longitude) }
        if (geometry.size < 2) {
            onComplete(emptyList(), "Stops skipped because the route line was unavailable.")
            return
        }

        onProgress(
            "Finding an open " +
                requests.joinToString(" and ") { it.first.progressLabel } +
                " along the route…",
        )
        Thread {
            val candidates =
                runCatching {
                    OpenStreetMapRouteStopRepository(appContext).stopsAlongRoute(
                        geometry,
                        requests.map { it.first.osmCategory }.toSet(),
                    )
                }.onFailure { Log.w(TAG, "OpenStreetMap route stop search failed", it) }
                    .getOrDefault(emptyList())
            val fuelStations =
                if (requests.any { it.first == RouteStopCategory.FUEL }) {
                    runCatching { TankerkoenigRepository(appContext).stationsAlongRoute(geometry) }
                        .onFailure { Log.w(TAG, "Tankerkoenig price lookup failed", it) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }

            val cumulative = RouteCameraAnalyzer.cumulativeDistances(geometry)
            val totalMeters = cumulative.last()
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val results = mutableListOf<OptimizedRouteStop>()
            val notes = mutableListOf<String>()

            requests.forEach { (category, mode) ->
                val categoryCandidates =
                    candidates
                        .filter { it.category == category.osmCategory }
                        .mapNotNull { candidate ->
                            createCandidate(
                                candidate,
                                category,
                                mode,
                                geometry,
                                cumulative,
                                totalMeters,
                                directMeters,
                                directSeconds,
                                now,
                                fuelStations,
                            )
                        }
                val best = selectCandidate(categoryCandidates, mode)
                if (best != null) {
                    results += best
                } else {
                    notes += "No confirmed-open ${category.progressLabel} was found along this route."
                }
            }

            mainHandler.post { onComplete(results, notes.joinToString(" ").ifBlank { null }) }
        }.start()
    }

    private fun createCandidate(
        candidate: OsmRouteStopCandidate,
        category: RouteStopCategory,
        mode: RouteStopMode,
        geometry: List<RoadCoordinate>,
        cumulative: List<Double>,
        totalMeters: Double,
        directMeters: Int,
        directSeconds: Int,
        now: ZonedDateTime,
        fuelStations: List<TankerkoenigStation>,
    ): OptimizedRouteStop? {
        if (category == RouteStopCategory.SUPERMARKET && !isSupportedSupermarket(candidate.name)) return null
        val projection = RouteCameraAnalyzer.projectOntoRoute(candidate.coordinate, geometry, cumulative)
        if (projection.crossTrackMeters > MAX_CROSS_TRACK_METERS) return null
        if (projection.alongMeters !in 0.0..totalMeters) return null

        // No road-network router is available for a free OSM candidate, so arrival
        // time is interpolated from the direct route's total time/distance, and the
        // detour is estimated as a there-and-back trip at a local-road speed.
        val fractionAlong =
            if (directMeters > 0) {
                (projection.alongMeters / directMeters.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        val arrival = now.plusSeconds((directSeconds * fractionAlong).toLong())
        val openingHours = candidate.openingHours
        val openAtArrival =
            openingHours != null &&
                OpeningHoursEvaluator.isOpenAt(openingHours, arrival) == true &&
                OpeningHoursEvaluator.isOpenAt(
                    openingHours,
                    arrival.plusSeconds(MINIMUM_OPEN_AFTER_ARRIVAL_SECONDS),
                ) == true

        val addedMeters = (projection.crossTrackMeters * 2).toInt()
        val addedSeconds = (addedMeters / DETOUR_METERS_PER_SECOND).toInt()

        // Enrichment only: Tankerkoenig's live price rides along on a proximity match, but
        // the OSM opening_hours check above stays the sole gate on whether this candidate
        // is even offered, so a missing/unmatched price never blocks a real stop.
        val matchedStation =
            if (category == RouteStopCategory.FUEL) {
                fuelStations
                    .map { it to RouteCameraAnalyzer.distanceMeters(candidate.coordinate, it.coordinate) }
                    .filter { (_, distance) -> distance <= FUEL_PRICE_MATCH_METERS }
                    .minByOrNull { (_, distance) -> distance }
                    ?.first
            } else {
                null
            }

        return OptimizedRouteStop(
            category = category,
            selectionMode = mode,
            stopId = candidate.id,
            coordinate = candidate.coordinate,
            title = candidate.name.ifBlank { category.label },
            metersFromRouteOrigin = projection.alongMeters.toInt(),
            addedMeters = addedMeters,
            addedSeconds = addedSeconds,
            arrivalEpochMillis = arrival.toInstant().toEpochMilli(),
            openAtArrival = openAtArrival,
            dieselPrice = matchedStation?.dieselPrice,
            e5Price = matchedStation?.e5Price,
            e10Price = matchedStation?.e10Price,
        )
    }

    companion object {
        private const val TAG = "RouteStopOptimizer"
        private const val MINIMUM_OPEN_AFTER_ARRIVAL_SECONDS = 15 * 60L
        private const val MAX_CROSS_TRACK_METERS = 2_000.0
        private const val DETOUR_METERS_PER_SECOND = 8.33 // ~30 km/h local-road estimate
        private const val FUEL_PRICE_MATCH_METERS = 150.0
        private val SUPERMARKET_NAMES = listOf("REWE", "KAUFLAND", "NETTO", "LIDL", "ALDI")

        internal fun isSupportedSupermarket(name: String): Boolean {
            val normalized = name.uppercase(Locale.GERMANY)
            return SUPERMARKET_NAMES.any(normalized::contains)
        }

        internal fun selectCandidate(
            candidates: List<OptimizedRouteStop>,
            mode: RouteStopMode,
        ): OptimizedRouteStop? {
            val openCandidates = candidates.filter { it.openAtArrival }
            val comparator =
                when (mode) {
                    RouteStopMode.NEED_NOW ->
                        compareBy<OptimizedRouteStop> { it.metersFromRouteOrigin }
                            .thenBy { it.addedMeters }
                            .thenBy { it.addedSeconds }
                    RouteStopMode.BEST_DETOUR ->
                        compareBy<OptimizedRouteStop> { it.addedMeters }
                            .thenBy { it.addedSeconds }
                            .thenBy { it.metersFromRouteOrigin }
                    RouteStopMode.OFF -> return null
                }
            return openCandidates.minWithOrNull(comparator)
        }

        private fun List<LatLng>.deduplicated(): List<LatLng> = filterIndexed { index, point -> index == 0 || point != this[index - 1] }
    }
}

enum class RouteStopCategory(
    val label: String,
    val progressLabel: String,
    val osmCategory: OsmRouteStopCategory,
) {
    SUPERMARKET(
        label = "Supermarket",
        progressLabel = "REWE, Kaufland, Netto, Lidl, or ALDI",
        osmCategory = OsmRouteStopCategory.SUPERMARKET,
    ),
    FUEL(
        label = "Fuel",
        progressLabel = "fuel station",
        osmCategory = OsmRouteStopCategory.FUEL,
    ),
}

data class OptimizedRouteStop(
    val category: RouteStopCategory,
    val selectionMode: RouteStopMode,
    val stopId: String,
    val coordinate: RoadCoordinate,
    val title: String,
    val metersFromRouteOrigin: Int,
    val addedMeters: Int,
    val addedSeconds: Int,
    val arrivalEpochMillis: Long,
    val openAtArrival: Boolean,
    val dieselPrice: Double? = null,
    val e5Price: Double? = null,
    val e10Price: Double? = null,
) {
    fun summary(): String {
        val distance =
            if (addedMeters < 1_000) {
                "+$addedMeters m"
            } else {
                String.format(Locale.GERMANY, "+%.1f km", addedMeters / 1_000.0)
            }
        val minutes = (addedSeconds + 59) / 60
        val icon = if (category == RouteStopCategory.SUPERMARKET) "🛒" else "⛽"
        val price = dieselPrice?.let { String.format(Locale.GERMANY, " · Diesel €%.3f", it) }.orEmpty()
        return "$icon ${selectionMode.shortLabel} · $title · open at ETA (est.) · $distance / +$minutes min$price"
    }
}

data class RouteStopPlan(
    val status: Navigator.RouteStatus,
    val stops: List<OptimizedRouteStop> = emptyList(),
    val note: String? = null,
) {
    fun summary(): String? =
        when {
            stops.isNotEmpty() -> stops.joinToString("\n") { it.summary() }
            !note.isNullOrBlank() -> note
            else -> null
        }
}
