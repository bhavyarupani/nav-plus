package com.navplus.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStyle
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import com.navplus.core.settings.SettingsRepository
import com.navplus.core.settings.UserSettings
import com.navplus.core.group.GroupSyncService
import com.navplus.core.group.model.GroupSession
import com.navplus.core.map.TrafficTileProvider
import com.navplus.core.navigation.LookaheadEngine
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.navigation.NavigationEngine
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RealWorldEngine
import com.navplus.core.navigation.RealWorldFrame
import com.navplus.core.navigation.RealWorldOptions
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.RoadCharacterAnalyzer
import com.navplus.core.navigation.RoadScenarioSimulator
import com.navplus.core.navigation.TripInsightsRepository
import com.navplus.core.navigation.TripRepository
import com.navplus.core.regions.BorderCrossing
import com.navplus.core.regions.BorderCrossingDetector
import com.navplus.core.routing.RoutingEngine
import com.navplus.core.routing.RoutingRequest
import com.navplus.core.routing.RoutingResult
import com.navplus.core.safety.OverpassCameraFetcher
import com.navplus.core.safety.SafetyEngine
import com.navplus.core.safety.SpeedCameraAssetSeeder
import com.navplus.core.safety.SpeedCameraRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Named

sealed class RoutingUiState {
    object Idle : RoutingUiState()
    object Calculating : RoutingUiState()
    object NoOfflineCoverage : RoutingUiState()
    data class Error(val message: String) : RoutingUiState()
    data class RouteReady(
        val choices: List<RouteChoice>,
        val selectedRouteId: String,
        val destinationName: String,
    ) : RoutingUiState() {
        val selectedChoice: RouteChoice get() =
            choices.firstOrNull { it.route.id == selectedRouteId } ?: choices.first()
        val route: Route get() = selectedChoice.route
        val distanceMeters: Double get() = route.distanceMeters
        val durationSeconds: Long get() = route.durationSeconds
    }
}

data class RouteChoice(
    val kind: RouteChoiceKind,
    val route: Route,
    val badge: String,
    val detail: String,
)

enum class RouteChoiceKind(
    val title: String,
    val style: RouteStyle,
    val description: String,
) {
    FASTEST("Fastest", RouteStyle.FASTEST, "Best ETA with live traffic"),
    SIMPLE("Simple", RouteStyle.SIMPLE, "Fewer turns and less tiny-road feeling"),
    SCENIC("Scenic", RouteStyle.SCENIC, "More pleasant roads when practical"),
    SAFER("Safer", RouteStyle.SAFER, "Lower junction complexity"),
    LOW_STRESS("Low-stress", RouteStyle.LOW_STRESS, "Fewer merges and roundabouts"),
}

@OptIn(FlowPreview::class)
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationEngine: NavigationEngine,
    private val locationTracker: LocationTracker,
    private val safetyEngine: SafetyEngine,
    private val routingEngine: RoutingEngine,
    private val tripRepository: TripRepository,
    private val tripInsightsRepository: TripInsightsRepository,
    private val lookaheadEngine: LookaheadEngine,
    private val roadCharacterAnalyzer: RoadCharacterAnalyzer,
    private val borderCrossingDetector: BorderCrossingDetector,
    private val groupSyncService: GroupSyncService,
    private val settingsRepository: SettingsRepository,
    private val roadScenarioSimulator: RoadScenarioSimulator,
    private val realWorldEngine: RealWorldEngine,
    private val speedCameraRepository: SpeedCameraRepository,
    private val overpassCameraFetcher: OverpassCameraFetcher,
    private val speedCameraAssetSeeder: SpeedCameraAssetSeeder,
    @Named("tomtom_api_key") private val tomTomApiKey: String,
) : ViewModel() {
    private var routeCalculationGeneration = 0L

    val navState: StateFlow<NavigationState> = navigationEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.Idle)

    val safetyAlerts = safetyEngine.alerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _routingUiState = MutableStateFlow<RoutingUiState>(RoutingUiState.Idle)
    val routingUiState: StateFlow<RoutingUiState> = _routingUiState.asStateFlow()

    private val _lookaheadEvents = MutableStateFlow<List<LookaheadEvent>>(emptyList())
    val lookaheadEvents: StateFlow<List<LookaheadEvent>> = _lookaheadEvents.asStateFlow()

    private val _roadCharacters = MutableStateFlow<List<RoadCharacter>>(emptyList())
    val roadCharacters: StateFlow<List<RoadCharacter>> = _roadCharacters.asStateFlow()

    private val _borderCrossings = MutableStateFlow<List<BorderCrossing>>(emptyList())
    val borderCrossings: StateFlow<List<BorderCrossing>> = _borderCrossings.asStateFlow()

    private val _realWorldFrame = MutableStateFlow(RealWorldFrame.Empty)
    val realWorldFrame: StateFlow<RealWorldFrame> = _realWorldFrame.asStateFlow()

    val groupSession: StateFlow<GroupSession?> = groupSyncService.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val trafficFlowTileUrls: StateFlow<List<String>> = settings
        .map { s ->
            if (s.trafficFeaturesEnabled && s.showTrafficLayer) {
                TrafficTileProvider.tomTomFlowTileUrls(tomTomApiKey)
            } else {
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Prevent NavigationScreen from immediately exiting while waiting for first location fix
        if (tripRepository.pending.value != null) {
            _routingUiState.value = RoutingUiState.Calculating
        }

        viewModelScope.launch {
            speedCameraAssetSeeder.ensureSeeded()
        }

        viewModelScope.launch {
            locationTracker.locationUpdates().collect { location ->
                _currentLocation.value = location

                // Wire search → route → navigation: trigger routing as soon as we have a fix
                val pending = tripRepository.consume()
                if (pending != null && navState.value == NavigationState.Idle) {
                    calculateAndStart(location.latLng, pending.destination, pending.destinationName)
                    return@collect
                }

                val state = navState.value
                if (state is NavigationState.Navigating) {
                    val progress = state.progress
                    safetyEngine.updatePosition(
                        position = progress.snappedLocation,
                        headingDeg = progress.routeBearingDeg,
                        speedKph = location.speedKph,
                    )
                    groupSyncService.broadcastLocation(
                        lat = progress.snappedLocation.lat,
                        lng = progress.snappedLocation.lng,
                        bearing = progress.routeBearingDeg,
                        speedKph = location.speedKph,
                        etaSec = progress.durationRemainingSeconds,
                        distanceRemainingMeters = progress.distanceRemainingMeters,
                        hasDeviated = progress.isOffRoute,
                    )
                    val distFromStart = progress.route.distanceMeters - progress.distanceRemainingMeters
                    _realWorldFrame.value = realWorldEngine.frameAhead(
                        route = progress.route,
                        currentPosition = progress.snappedLocation,
                        headingDeg = progress.routeBearingDeg,
                        distanceFromStartMeters = distFromStart,
                        currentSpeedKph = location.speedKph,
                        options = settings.value.toRealWorldOptions(),
                    )
                }
            }
        }

        viewModelScope.launch {
            navState.debounce(2_000).collect { state ->
                if (state is NavigationState.Navigating) {
                    val route = state.progress.route
                    val distFromStart = route.distanceMeters - state.progress.distanceRemainingMeters
                    _lookaheadEvents.value = lookaheadEngine.eventsAhead(
                        route = route,
                        currentDistanceFromStartMeters = distFromStart,
                        currentSpeedKph = _currentLocation.value?.speedKph,
                    )
                    _roadCharacters.value = roadCharacterAnalyzer.analyzeAhead(route, state.progress.currentStepIndex)
                    _borderCrossings.value = borderCrossingDetector.detectCrossings(route, distFromStart)
                    _realWorldFrame.value = realWorldEngine.frameAhead(
                        route = route,
                        currentPosition = state.progress.snappedLocation,
                        headingDeg = state.progress.routeBearingDeg,
                        distanceFromStartMeters = distFromStart,
                        currentSpeedKph = _currentLocation.value?.speedKph,
                        options = settings.value.toRealWorldOptions(),
                    )
                } else {
                    _lookaheadEvents.value = emptyList()
                    _roadCharacters.value = emptyList()
                    _borderCrossings.value = emptyList()
                    _realWorldFrame.value = RealWorldFrame.Empty
                }
            }
        }
    }

    private suspend fun calculateAndStart(origin: LatLng, destination: LatLng, destinationName: String) {
        val generation = ++routeCalculationGeneration
        _routingUiState.value = RoutingUiState.Calculating
        val s = settings.value

        val quickChoices = calculateQuickRouteChoices(origin, destination, s)
        if (quickChoices.isNotEmpty()) {
            publishRouteReady(
                generation = generation,
                choices = quickChoices,
                destinationName = destinationName,
            )
            viewModelScope.launch {
                val enriched = calculateRouteChoices(origin, destination, s)
                if (enriched.isNotEmpty()) {
                    publishRouteReady(
                        generation = generation,
                        choices = enriched,
                        destinationName = destinationName,
                    )
                }
            }
            return
        }

        val choices = calculateRouteChoices(origin, destination, s)
        if (choices.isNotEmpty()) {
            publishRouteReady(
                generation = generation,
                choices = choices,
                destinationName = destinationName,
            )
            return
        }

        val fallback = RoutingRequest(
            origin = origin,
            destination = destination,
            avoidTolls = s.avoidTolls,
            avoidHighways = s.avoidHighways,
            avoidFerries = s.avoidFerries,
        )
        when (val result = calculateRoutesWithinTimeout(fallback)) {
            is RoutingResult.Success -> {
                if (result.routes.isEmpty()) {
                    _routingUiState.value = RoutingUiState.Error("No route alternatives returned")
                } else {
                    publishRouteReady(
                        generation = generation,
                        choices = result.routes.take(3).mapIndexed { index, route ->
                            route.toRouteChoice(
                                kind = if (index == 0) RouteChoiceKind.FASTEST else RouteChoiceKind.SIMPLE,
                                baselineSeconds = result.routes.firstOrNull()?.durationSeconds,
                            )
                        },
                        destinationName = destinationName,
                    )
                }
            }
            is RoutingResult.NoOfflineCoverage -> _routingUiState.value = RoutingUiState.NoOfflineCoverage
            is RoutingResult.Error -> _routingUiState.value = RoutingUiState.Error(
                result.cause.message ?: "Routing failed"
            )
        }
    }

    private fun publishRouteReady(
        generation: Long,
        choices: List<RouteChoice>,
        destinationName: String,
    ) {
        if (generation != routeCalculationGeneration) return
        val current = _routingUiState.value
        if (current !is RoutingUiState.Calculating && current !is RoutingUiState.RouteReady) return
        val selectedRouteId = (current as? RoutingUiState.RouteReady)
            ?.selectedRouteId
            ?.takeIf { selected -> choices.any { it.route.id == selected } }
            ?: choices.first().route.id
        _routingUiState.value = RoutingUiState.RouteReady(
            choices = choices,
            selectedRouteId = selectedRouteId,
            destinationName = destinationName,
        )
    }

    private suspend fun calculateQuickRouteChoices(
        origin: LatLng,
        destination: LatLng,
        settings: UserSettings,
    ): List<RouteChoice> {
        val request = RoutingRequest(
            origin = origin,
            destination = destination,
            style = RouteStyle.FASTEST,
            alternatives = 3,
            avoidTolls = settings.avoidTolls,
            avoidHighways = settings.avoidHighways,
            avoidFerries = settings.avoidFerries,
        )
        val routes = (calculateRoutesWithinTimeout(request, QUICK_ROUTE_TIMEOUT_MS) as? RoutingResult.Success)
            ?.routes
            .orEmpty()
        if (routes.isEmpty()) return emptyList()
        val baselineSeconds = routes.fastestSeconds()
        return buildList {
            RouteChoiceKind.FASTEST.selectBest(routes)?.copy(style = RouteStyle.FASTEST)?.let {
                add(it.toRouteChoice(RouteChoiceKind.FASTEST, baselineSeconds))
            }
            RouteChoiceKind.SIMPLE.selectBest(routes)?.copy(style = RouteStyle.SIMPLE)?.let {
                add(it.toRouteChoice(RouteChoiceKind.SIMPLE, baselineSeconds))
            }
            RouteChoiceKind.SAFER.selectBest(routes)?.copy(style = RouteStyle.SAFER)?.let {
                add(it.toRouteChoice(RouteChoiceKind.SAFER, baselineSeconds))
            }
        }.dedupeChoices()
    }

    private suspend fun calculateRouteChoices(
        origin: LatLng,
        destination: LatLng,
        settings: UserSettings,
    ): List<RouteChoice> = coroutineScope {
        val requests = listOf(
            RouteChoiceKind.FASTEST to RoutingRequest(
                origin = origin,
                destination = destination,
                style = RouteStyle.FASTEST,
                alternatives = 3,
                avoidTolls = settings.avoidTolls,
                avoidHighways = settings.avoidHighways,
                avoidFerries = settings.avoidFerries,
            ),
            RouteChoiceKind.SCENIC to RoutingRequest(
                origin = origin,
                destination = destination,
                style = RouteStyle.SCENIC,
                alternatives = 2,
                avoidTolls = settings.avoidTolls,
                avoidHighways = true,
                avoidFerries = settings.avoidFerries,
            ),
            RouteChoiceKind.LOW_STRESS to RoutingRequest(
                origin = origin,
                destination = destination,
                style = RouteStyle.LOW_STRESS,
                alternatives = 3,
                avoidTolls = settings.avoidTolls,
                avoidHighways = true,
                avoidFerries = true,
            ),
        )
        val results = requests.map { (kind, request) ->
            async { kind to calculateRoutesWithinTimeout(request) }
        }.awaitAll()

        val baselineSeconds = results
            .asSequence()
            .mapNotNull { (_, result) -> (result as? RoutingResult.Success)?.routes?.minOfOrNull { it.durationSeconds } }
            .minOrNull()

        val routesByKind = results.associate { (kind, result) ->
            kind to (result as? RoutingResult.Success)?.routes.orEmpty()
        }
        val fastestRoutes = routesByKind[RouteChoiceKind.FASTEST].orEmpty()
        val allChoices = mutableListOf<RouteChoice>()
        val baseline = baselineSeconds ?: fastestRoutes.fastestSeconds().takeIf { it > 0L }

        RouteChoiceKind.FASTEST.selectBest(fastestRoutes)?.copy(style = RouteChoiceKind.FASTEST.style)?.let {
            allChoices += it.toRouteChoice(RouteChoiceKind.FASTEST, baseline)
        }
        RouteChoiceKind.SIMPLE.selectBest(fastestRoutes)?.copy(style = RouteChoiceKind.SIMPLE.style)?.let {
            allChoices += it.toRouteChoice(RouteChoiceKind.SIMPLE, baseline)
        }
        RouteChoiceKind.SAFER.selectBest(fastestRoutes)?.copy(style = RouteChoiceKind.SAFER.style)?.let {
            allChoices += it.toRouteChoice(RouteChoiceKind.SAFER, baseline)
        }

        results.mapNotNullTo(allChoices) { (kind, result) ->
            if (kind == RouteChoiceKind.FASTEST) return@mapNotNullTo null
            val routes = (result as? RoutingResult.Success)?.routes.orEmpty()
            val route = kind.selectBest(routes)?.copy(style = kind.style) ?: return@mapNotNullTo null
            route.toRouteChoice(kind, baseline)
        }
        allChoices.dedupeChoices()
    }

    private suspend fun calculateRoutesWithinTimeout(
        request: RoutingRequest,
        timeoutMs: Long = ROUTE_REQUEST_TIMEOUT_MS,
    ): RoutingResult =
        withTimeoutOrNull(timeoutMs) { routingEngine.calculateRoutes(request) }
            ?: RoutingResult.Error(Exception("Routing timed out"))

    fun selectRoute(routeId: String) {
        val ready = _routingUiState.value as? RoutingUiState.RouteReady ?: return
        if (ready.choices.none { it.route.id == routeId }) return
        _routingUiState.value = ready.copy(selectedRouteId = routeId)
    }

    fun startNavigation(route: Route) {
        routeCalculationGeneration++
        _routingUiState.value = RoutingUiState.Idle
        safetyEngine.setRoute(route)
        if (groupSession.value?.isLeader == true) {
            groupSyncService.broadcastRoute(route)
        }
        navigationEngine.startNavigation(route)
        if (settings.value.showSpeedCameras) {
            viewModelScope.launch {
                overpassCameraFetcher.fetchRouteCorridor(route.geometry)
                val state = navState.value as? NavigationState.Navigating ?: return@launch
                val distFromStart = state.progress.route.distanceMeters - state.progress.distanceRemainingMeters
                _lookaheadEvents.value = lookaheadEngine.eventsAhead(
                    route = state.progress.route,
                    currentDistanceFromStartMeters = distFromStart,
                    currentSpeedKph = _currentLocation.value?.speedKph,
                )
            }
        }
    }

    private fun RouteChoiceKind.selectBest(routes: List<Route>): Route? = when (this) {
        RouteChoiceKind.FASTEST -> routes.minByOrNull { it.durationSeconds }
        RouteChoiceKind.SIMPLE -> routes.minByOrNull { it.simpleScore() }
        RouteChoiceKind.SCENIC -> routes.minByOrNull { it.scenicScore(routes.fastestSeconds()) }
        RouteChoiceKind.SAFER -> routes.minByOrNull { it.safetyScore() }
        RouteChoiceKind.LOW_STRESS -> routes.minByOrNull { it.lowStressScore() }
    }

    private fun Route.toRouteChoice(kind: RouteChoiceKind, baselineSeconds: Long?): RouteChoice {
        val delta = baselineSeconds?.let { durationSeconds - it } ?: 0L
        val traffic = trafficDelaySeconds
        val badge = when {
            traffic >= 8 * 60 -> "+${traffic / 60} min traffic"
            traffic >= 3 * 60 -> "traffic +${traffic / 60} min"
            traffic > 0 -> "light traffic"
            delta <= 60 -> "clear"
            else -> "+${(delta / 60).coerceAtLeast(1)} min"
        }
        val detail = buildString {
            append(kind.description)
            append(" · ")
            append(effectiveTurnCount())
            append(" turns")
            val roundabouts = roundaboutCount()
            if (roundabouts > 0) append(" · $roundabouts roundabouts")
        }
        return RouteChoice(kind = kind, route = this, badge = badge, detail = detail)
    }

    private fun List<RouteChoice>.dedupeChoices(): List<RouteChoice> {
        val seen = mutableSetOf<String>()
        return filter { choice ->
            seen.add(choice.route.shapeKey())
        }.take(5)
    }

    private fun List<Route>.fastestSeconds(): Long =
        minOfOrNull { it.durationSeconds } ?: 0L

    private fun Route.simpleScore(): Double =
        durationSeconds + effectiveTurnCount() * 75.0 + roundaboutCount() * 90.0 + unnamedStepCount() * 30.0

    private fun Route.scenicScore(fastestSeconds: Long): Double {
        val extraTimePenalty = (durationSeconds - fastestSeconds).coerceAtLeast(0) * 0.65
        val motorwayPenalty = if (hasHighways) 600.0 else 0.0
        return extraTimePenalty + motorwayPenalty + effectiveTurnCount() * 15.0
    }

    private fun Route.safetyScore(): Double =
        durationSeconds + junctionCount() * 90.0 + roundaboutCount() * 70.0 + sharpTurnCount() * 120.0

    private fun Route.lowStressScore(): Double =
        durationSeconds + rampCount() * 140.0 + roundaboutCount() * 160.0 + effectiveTurnCount() * 45.0

    private fun Route.turnCount(): Int =
        steps.count { it.maneuver in turnManeuvers }

    private fun Route.effectiveTurnCount(): Int =
        turnCount().takeIf { it > 0 } ?: geometryTurnCount()

    private fun Route.geometryTurnCount(): Int {
        if (geometry.size < 3) return 0
        var turns = 0
        var lastBearing: Double? = null
        var distanceSinceTurn = 0.0
        for (index in 1 until geometry.lastIndex) {
            val previous = geometry[index - 1]
            val current = geometry[index]
            val next = geometry[index + 1]
            val segDistance = current.distanceTo(next)
            if (segDistance < 18.0) continue
            val bearing = current.bearingTo(next)
            val previousBearing = lastBearing ?: previous.bearingTo(current).also { lastBearing = it }
            distanceSinceTurn += segDistance
            if (distanceSinceTurn >= 55.0 && angleDifference(previousBearing, bearing) >= 38.0) {
                turns++
                distanceSinceTurn = 0.0
            }
            lastBearing = bearing
        }
        return turns
    }

    private fun Route.roundaboutCount(): Int =
        steps.count { it.maneuver == Maneuver.ROUNDABOUT_ENTER || it.maneuver == Maneuver.ROUNDABOUT_EXIT }

    private fun Route.junctionCount(): Int =
        steps.count { it.maneuver in junctionManeuvers }

    private fun Route.rampCount(): Int =
        steps.count { it.maneuver == Maneuver.ON_RAMP || it.maneuver == Maneuver.OFF_RAMP || it.maneuver == Maneuver.MERGE_LEFT || it.maneuver == Maneuver.MERGE_RIGHT }

    private fun Route.sharpTurnCount(): Int =
        steps.count { it.maneuver == Maneuver.TURN_SHARP_LEFT || it.maneuver == Maneuver.TURN_SHARP_RIGHT || it.maneuver == Maneuver.U_TURN }

    private fun Route.unnamedStepCount(): Int =
        steps.count { it.streetName.isNullOrBlank() && it.instruction.isBlank() }

    private fun Route.shapeKey(): String {
        val samples = listOfNotNull(
            geometry.firstOrNull(),
            geometry.getOrNull(geometry.size / 2),
            geometry.lastOrNull(),
        ).joinToString("|") { "${(it.lat * 10_000).toInt()},${(it.lng * 10_000).toInt()}" }
        return "${(distanceMeters / 100).toInt()}|$samples"
    }

    private fun angleDifference(a: Double, b: Double): Double {
        val diff = ((b - a) % 360.0 + 540.0) % 360.0 - 180.0
        return kotlin.math.abs(diff)
    }

    private companion object {
        const val QUICK_ROUTE_TIMEOUT_MS = 7_000L
        const val ROUTE_REQUEST_TIMEOUT_MS = 12_000L

        val turnManeuvers = setOf(
            Maneuver.TURN_LEFT,
            Maneuver.TURN_RIGHT,
            Maneuver.TURN_SLIGHT_LEFT,
            Maneuver.TURN_SLIGHT_RIGHT,
            Maneuver.TURN_SHARP_LEFT,
            Maneuver.TURN_SHARP_RIGHT,
            Maneuver.U_TURN,
            Maneuver.KEEP_LEFT,
            Maneuver.KEEP_RIGHT,
        )
        val junctionManeuvers = turnManeuvers + setOf(
            Maneuver.ON_RAMP,
            Maneuver.OFF_RAMP,
            Maneuver.MERGE_LEFT,
            Maneuver.MERGE_RIGHT,
            Maneuver.FORK_LEFT,
            Maneuver.FORK_RIGHT,
        )
    }

    fun startRoadSimulation() {
        val scenario = roadScenarioSimulator.scenario
        viewModelScope.launch {
            speedCameraRepository.upsertCameras(scenario.cameras)
            roadScenarioSimulator.start()
            groupSyncService.startDebugConvoy(scenario.route)
            startNavigation(scenario.route)
        }
    }

    fun stopNavigation() {
        val activeRoute = (navState.value as? NavigationState.Navigating)?.progress?.route
        if (activeRoute != null && settings.value.tripHistoryEnabled) {
            viewModelScope.launch {
                tripInsightsRepository.saveCompletedRoute(activeRoute, activeRoute.tripInsightTitle())
            }
        }
        navigationEngine.stopNavigation()
        safetyEngine.clearRoute()
        if (roadScenarioSimulator.isActive) {
            roadScenarioSimulator.stop()
        }
        _routingUiState.value = RoutingUiState.Idle
    }
}

private fun Route.tripInsightTitle(): String =
    waypoints.lastOrNull()?.let { "Trip to ${"%.3f".format(it.lat)}, ${"%.3f".format(it.lng)}" }
        ?: "Completed trip"

private fun UserSettings.toRealWorldOptions(): RealWorldOptions = RealWorldOptions(
    enabled = realWorldFeelEnabled,
    visibleAircraft = showVisibleAircraft,
    airportApproach = showAirportApproach,
    railCrossing = showRailCrossingIntelligence,
    skyAndLight = showSkyAndLightReality,
    sunGlare = showSunGlareWarning,
    landmarkGlance = showRoadsideLandmarks,
    waterFerryBridge = showWaterFerryBridgeMoments,
    wildlifeRisk = showWildlifeRiskAtmosphere,
    eventCrowd = showEventCrowdPulse,
    roadFeel = showRoadFeelMode,
    windFlow = showWindFlow,
    fogDepth = showFogDepthLayer,
    stormCell = showStormCellEncounter,
    ambientRoutePulse = showAmbientRoutePulse,
    emergencyAwareness = showEmergencyVehicleAwareness,
    roadSurfaceFeel = showRoadSurfaceFeel,
    destinationArrivalMood = showDestinationArrivalMood,
    realWeatherAhead = showRealWeatherAhead,
    moonNightSky = showMoonNightSky,
    visibleHazardScene = showVisibleHazardScene,
)
