package com.navplus.app.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Signboard
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.navigation.LookaheadEngine
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LookaheadEventType
import com.navplus.core.navigation.NavigationEngine
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RouteProgress
import com.navplus.core.safety.SafetyEngine
import com.navplus.core.safety.model.SafetyAlert
import com.navplus.core.settings.SettingsRepository
import com.navplus.core.settings.UserSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class NavPlusCarAppService : CarAppService() {
    @Inject lateinit var navigationEngine: NavigationEngine
    @Inject lateinit var safetyEngine: SafetyEngine
    @Inject lateinit var lookaheadEngine: LookaheadEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var locationTracker: LocationTracker

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return NavPlusCarSession(
            navigationEngine = navigationEngine,
            safetyEngine = safetyEngine,
            lookaheadEngine = lookaheadEngine,
            settingsRepository = settingsRepository,
            locationTracker = locationTracker,
        )
    }
}

private class NavPlusCarSession(
    private val navigationEngine: NavigationEngine,
    private val safetyEngine: SafetyEngine,
    private val lookaheadEngine: LookaheadEngine,
    private val settingsRepository: SettingsRepository,
    private val locationTracker: LocationTracker,
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return NavPlusCarScreen(
            carContext = carContext,
            navigationEngine = navigationEngine,
            safetyEngine = safetyEngine,
            lookaheadEngine = lookaheadEngine,
            settingsRepository = settingsRepository,
            locationTracker = locationTracker,
        )
    }
}

private class NavPlusCarScreen(
    carContext: CarContext,
    private val navigationEngine: NavigationEngine,
    private val safetyEngine: SafetyEngine,
    private val lookaheadEngine: LookaheadEngine,
    private val settingsRepository: SettingsRepository,
    private val locationTracker: LocationTracker,
) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectionJob: Job? = null
    private var screenState = CarScreenState()
    private var lookaheadCacheKey: String? = null
    private var lookaheadCache: List<LookaheadEvent> = emptyList()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startCollecting()
            }

            override fun onStop(owner: LifecycleOwner) {
                collectionJob?.cancel()
                collectionJob = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        when (val navState = screenState.navigationState) {
            NavigationState.Idle -> buildIdlePane(pane)
            NavigationState.Rerouting -> buildStatusPane(pane, "Rerouting", "Finding a stable route.")
            NavigationState.RouteUnavailable -> buildStatusPane(pane, "Route unavailable", "Check the route on your phone.")
            is NavigationState.Navigating -> buildNavigationPane(pane, navState.progress)
        }

        val builder = PaneTemplate.Builder(pane.build())
            .setTitle("Nav Plus")

        if (screenState.navigationState is NavigationState.Navigating) {
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Stop")
                            .setOnClickListener {
                                navigationEngine.stopNavigation()
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
        }

        return builder.build()
    }

    private fun startCollecting() {
        if (collectionJob != null) return
        collectionJob = scope.launch {
            combine(
                navigationEngine.state,
                safetyEngine.alerts,
                settingsRepository.settings,
                locationTracker.locationUpdates(intervalMs = 1_000)
                    .map { it as Location? }
                    .onStart { emit(null) }
                    .catch { emit(null) },
            ) { navigationState, alerts, settings, location ->
                val lookahead = if (navigationState is NavigationState.Navigating) {
                    resolveLookahead(navigationState.progress, location, settings)
                } else {
                    emptyList()
                }
                CarScreenState(
                    navigationState = navigationState,
                    alerts = visibleAlerts(alerts, settings),
                    settings = settings,
                    location = location,
                    lookahead = visibleLookahead(lookahead, settings),
                )
            }.collect { next ->
                screenState = next
                invalidate()
            }
        }
    }

    private fun buildIdlePane(pane: Pane.Builder) {
        pane.addRow(
            Row.Builder()
                .setTitle("Start navigation on your phone")
                .addText("Android Auto will follow the active Nav Plus route.")
                .build()
        )
        pane.addRow(
            Row.Builder()
                .setTitle("Driving display ready")
                .addText("Speed, lane guidance, cameras, and road-ahead warnings are shown during navigation.")
                .build()
        )
    }

    private fun buildStatusPane(pane: Pane.Builder, title: String, text: String) {
        pane.addRow(
            Row.Builder()
                .setTitle(title)
                .addText(text)
                .build()
        )
    }

    private fun buildNavigationPane(pane: Pane.Builder, progress: RouteProgress) {
        pane.addRow(
            Row.Builder()
                .setTitle(maneuverText(progress.nextManeuver, progress.nextInstruction))
                .addText("${formatDistance(progress.distanceToNextStepMeters)} to next step")
                .addText("${formatDistance(progress.distanceRemainingMeters)} left · ${formatDuration(progress.durationRemainingSeconds)}")
                .build()
        )

        speedLine(progress)?.let { line ->
            pane.addRow(
                Row.Builder()
                    .setTitle(line.title)
                    .addText(line.body)
                    .build()
            )
        }

        laneLine(progress.laneGuidance, screenState.settings)?.let { line ->
            pane.addRow(
                Row.Builder()
                    .setTitle("Lane guidance")
                    .addText(line)
                    .build()
            )
        }

        signboardLine(progress.signboard, screenState.settings)?.let { line ->
            pane.addRow(
                Row.Builder()
                    .setTitle("Road sign")
                    .addText(line)
                    .build()
            )
        }

        screenState.alerts.firstOrNull()?.let { alert ->
            pane.addRow(
                Row.Builder()
                    .setTitle(alert.title)
                    .addText(cameraAlertText(alert))
                    .build()
            )
        }

        screenState.lookahead.take(3).forEach { event ->
            pane.addRow(
                Row.Builder()
                    .setTitle("${event.title} in ${formatDistance(event.distanceMeters)}")
                    .addText(event.subtitle ?: event.type.name.lowercase().replace('_', ' '))
                    .build()
            )
        }
    }

    private suspend fun resolveLookahead(
        progress: RouteProgress,
        location: Location?,
        settings: UserSettings,
    ): List<LookaheadEvent> {
        if (!settings.showRoadAhead) return emptyList()

        val currentDistance = (progress.route.distanceMeters - progress.distanceRemainingMeters)
            .coerceIn(0.0, progress.route.distanceMeters)
        val distanceBucket = (currentDistance / LOOKAHEAD_REFRESH_METERS).roundToInt()
        val speedBucket = (((location?.speedKph ?: -1f) / SPEED_REFRESH_KPH).roundToInt())
        val key = listOf(
            progress.route.id,
            distanceBucket,
            speedBucket,
            settings.roadAheadMaxItems,
            settings.safetyFeaturesEnabled,
            settings.showSpeedCameras,
            settings.trafficSignalIntelligence,
        ).joinToString("|")

        if (key == lookaheadCacheKey) return lookaheadCache

        return withContext(Dispatchers.Default) {
            lookaheadEngine.eventsAhead(
                route = progress.route,
                currentDistanceFromStartMeters = currentDistance,
                currentSpeedKph = location?.speedKph,
            )
        }.also {
            lookaheadCacheKey = key
            lookaheadCache = it
        }
    }

    private fun visibleAlerts(alerts: List<SafetyAlert>, settings: UserSettings): List<SafetyAlert> {
        if (!settings.safetyFeaturesEnabled || !settings.showSpeedCameras) return emptyList()
        return alerts.sortedBy { it.distanceMeters }.take(1)
    }

    private fun visibleLookahead(
        events: List<LookaheadEvent>,
        settings: UserSettings,
    ): List<LookaheadEvent> {
        if (!settings.showRoadAhead) return emptyList()
        return events.asSequence()
            .filter { event ->
                when (event.type) {
                    LookaheadEventType.SPEED_CAMERA -> settings.safetyFeaturesEnabled &&
                        settings.showSpeedCameras &&
                        settings.roadAheadShowCameras
                    LookaheadEventType.TRAFFIC_SIGNAL -> settings.trafficSignalIntelligence &&
                        settings.roadAheadShowTraffic
                    LookaheadEventType.ROADWORK -> settings.roadAheadShowRoadworks
                    LookaheadEventType.WEATHER -> settings.roadAheadShowWeather
                    LookaheadEventType.FUEL_STATION -> settings.roadAheadShowFuel
                    LookaheadEventType.REST_AREA -> settings.roadAheadShowRestAreas
                    LookaheadEventType.BORDER_CROSSING -> settings.roadAheadShowBorders
                    else -> true
                }
            }
            .sortedBy { it.distanceMeters }
            .take(settings.roadAheadMaxItems.coerceIn(1, 5))
            .toList()
    }

    private fun speedLine(progress: RouteProgress): SpeedLine? {
        val settings = screenState.settings
        val currentSpeed = screenState.location?.speedKph?.roundToInt()
        val limit = progress.speedLimitKph
        if (!settings.showCurrentSpeed && !settings.showSpeedLimit) return null
        val title = when {
            settings.showCurrentSpeed && currentSpeed != null -> "Speed $currentSpeed km/h"
            settings.showSpeedLimit && limit != null -> "Speed limit $limit km/h"
            else -> return null
        }
        val parts = mutableListOf<String>()
        if (settings.showSpeedLimit && limit != null) parts += "limit $limit km/h"
        if (settings.showCurrentSpeed && currentSpeed != null && limit != null && currentSpeed > limit) {
            parts += "over by ${currentSpeed - limit} km/h"
        }
        val accuracy = screenState.location?.speedAccuracyMps
        if (settings.showCurrentSpeed && accuracy != null) {
            parts += "GPS +/- ${(accuracy * 3.6f).roundToInt()} km/h"
        }
        return SpeedLine(title, parts.ifEmpty { listOf("GPS speed active") }.joinToString(" · "))
    }

    private fun laneLine(laneGuidance: LaneGuidance?, settings: UserSettings): String? {
        if (!settings.showLaneGuidance || laneGuidance == null || laneGuidance.lanes.isEmpty()) return null
        return laneGuidance.lanes.mapIndexed { index, lane ->
            val directions = lane.directions.joinToString("/") { it.shortLabel() }
            val recommended = index in laneGuidance.recommendedIndices || lane.isActive
            if (recommended) "[$directions]" else directions
        }.joinToString(" | ")
    }

    private fun LaneDirection.shortLabel(): String = when (this) {
        LaneDirection.STRAIGHT -> "straight"
        LaneDirection.LEFT -> "left"
        LaneDirection.SLIGHT_LEFT -> "slight left"
        LaneDirection.SHARP_LEFT -> "sharp left"
        LaneDirection.RIGHT -> "right"
        LaneDirection.SLIGHT_RIGHT -> "slight right"
        LaneDirection.SHARP_RIGHT -> "sharp right"
        LaneDirection.U_TURN -> "u-turn"
        LaneDirection.MERGE -> "merge"
        LaneDirection.EXIT -> "exit"
    }

    private fun signboardLine(signboard: Signboard?, settings: UserSettings): String? {
        if (!settings.showSignboards || signboard == null) return null
        val parts = mutableListOf<String>()
        if (settings.showExitNumbers) signboard.exitNumber?.let { parts += "exit $it" }
        if (settings.showRoadNumbers) signboard.roadNumber?.let { parts += it }
        if (settings.showDestinationNames) parts += signboard.destinations.take(2)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun cameraAlertText(alert: SafetyAlert): String {
        val parts = mutableListOf(formatDistance(alert.distanceMeters))
        alert.speedLimitKph?.let { parts += "$it km/h" }
        parts += alert.severity.name.lowercase()
        return parts.joinToString(" · ")
    }

    private fun maneuverText(maneuver: Maneuver, instruction: String): String {
        if (instruction.isNotBlank()) return instruction
        return when (maneuver) {
            Maneuver.DEPART -> "Start"
            Maneuver.ARRIVE -> "Arrive"
            Maneuver.STRAIGHT -> "Continue straight"
            Maneuver.TURN_LEFT -> "Turn left"
            Maneuver.TURN_RIGHT -> "Turn right"
            Maneuver.TURN_SLIGHT_LEFT -> "Slight left"
            Maneuver.TURN_SLIGHT_RIGHT -> "Slight right"
            Maneuver.TURN_SHARP_LEFT -> "Sharp left"
            Maneuver.TURN_SHARP_RIGHT -> "Sharp right"
            Maneuver.U_TURN -> "Make a U-turn"
            Maneuver.ROUNDABOUT_ENTER -> "Enter roundabout"
            Maneuver.ROUNDABOUT_EXIT -> "Exit roundabout"
            Maneuver.FORK_LEFT -> "Keep left at fork"
            Maneuver.FORK_RIGHT -> "Keep right at fork"
            Maneuver.MERGE_LEFT -> "Merge left"
            Maneuver.MERGE_RIGHT -> "Merge right"
            Maneuver.ON_RAMP -> "Take ramp"
            Maneuver.OFF_RAMP -> "Take exit"
            Maneuver.FERRY -> "Take ferry"
            Maneuver.TUNNEL -> "Enter tunnel"
            Maneuver.KEEP_LEFT -> "Keep left"
            Maneuver.KEEP_RIGHT -> "Keep right"
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 950.0) {
            val km = meters / 1_000.0
            if (km >= 10) "${km.roundToInt()} km" else String.format("%.1f km", km)
        } else {
            "${meters.roundToInt()} m"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = (seconds / 60).coerceAtLeast(0)
        val hours = minutes / 60
        val rem = minutes % 60
        return if (hours > 0) "${hours}h ${rem}m" else "${minutes} min"
    }

    private data class SpeedLine(val title: String, val body: String)

    companion object {
        private const val LOOKAHEAD_REFRESH_METERS = 100.0
        private const val SPEED_REFRESH_KPH = 5.0f
    }
}

private data class CarScreenState(
    val navigationState: NavigationState = NavigationState.Idle,
    val alerts: List<SafetyAlert> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val location: Location? = null,
    val lookahead: List<LookaheadEvent> = emptyList(),
)
