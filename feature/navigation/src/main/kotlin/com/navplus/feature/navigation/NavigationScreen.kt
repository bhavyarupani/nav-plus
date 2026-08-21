package com.navplus.feature.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.view.WindowManager
import com.navplus.core.common.model.Lane
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.Signboard
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import com.navplus.core.group.model.GroupSession
import com.navplus.core.map.CameraMarker
import com.navplus.core.map.ConvoyMapMember
import com.navplus.core.map.MapRouteLine
import com.navplus.core.map.MapStyleProvider
import com.navplus.core.map.NavMapView
import com.navplus.core.map.RealWorldMapMarker
import com.navplus.core.navigation.RealWorldCue
import com.navplus.core.navigation.RealWorldCueType
import com.navplus.core.navigation.RealWorldFrame
import com.navplus.core.navigation.RealWorldSky
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LookaheadEventType
import com.navplus.core.navigation.LookaheadSeverity
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.RoadCharacterAnalyzer
import com.navplus.core.navigation.RouteProgress
import com.navplus.core.regions.BorderCrossing
import com.navplus.core.common.model.Severity
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SafetyAlert
import com.navplus.core.common.model.LatLng
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.SpeedWarningThreshold
import com.navplus.core.settings.UserSettings
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun NavigationScreen(
    onExit: () -> Unit,
    vm: NavigationViewModel = hiltViewModel(),
) {
    val navState by vm.navState.collectAsStateWithLifecycle()
    val routingUiState by vm.routingUiState.collectAsStateWithLifecycle()
    val location by vm.currentLocation.collectAsStateWithLifecycle()
    val alerts by vm.safetyAlerts.collectAsStateWithLifecycle()
    val lookaheadEvents by vm.lookaheadEvents.collectAsStateWithLifecycle()
    val roadCharacters by vm.roadCharacters.collectAsStateWithLifecycle()
    val borderCrossings by vm.borderCrossings.collectAsStateWithLifecycle()
    val realWorldFrame by vm.realWorldFrame.collectAsStateWithLifecycle()
    val groupSession by vm.groupSession.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trafficFlowTileUrls by vm.trafficFlowTileUrls.collectAsStateWithLifecycle()

    var showQuickOptions by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val navigatingState = navState as? NavigationState.Navigating
    val readyState = routingUiState as? RoutingUiState.RouteReady
    val activeProgress = navigatingState?.progress
    val mapCenter = activeProgress?.snappedLocation ?: location?.latLng ?: LatLng(48.1351, 11.5820)
    val bearing = activeProgress?.routeBearingDeg ?: location?.bearingDeg ?: 0f
    val routeGeometry: List<LatLng>? =
        activeProgress?.route?.geometry
    val routePulseColor =
        if (settings.realWorldFeelEnabled && settings.showAmbientRoutePulse) {
            realWorldFrame.atmosphere.routePulseColor
        } else {
            "#3B82F6"
        }
    val previewRoutes = readyState?.choices
        ?.map { MapRouteLine(id = it.route.id, geometry = it.route.geometry) }
        .orEmpty()
    val routeCameraMarkers = if (
        navigatingState != null &&
        settings.safetyFeaturesEnabled &&
        settings.showSpeedCameras
    ) {
        alerts.mapNotNull { alert ->
            alert.camera?.let { camera ->
                CameraMarker(
                    position = camera.position,
                    speedLimitKph = camera.speedLimitKph,
                    typeCode = camera.type.name,
                    id = camera.id,
                    source = camera.source,
                    confidence = camera.confidence,
                    lastUpdatedMs = camera.lastUpdatedMs,
                    country = camera.country,
                )
            }
        }
    } else {
        emptyList()
    }
    val convoyMapMembers = if (
        navigatingState != null &&
        settings.groupDriveEnabled &&
        settings.showGroupCarsOnMap
    ) {
        groupSession?.members
            ?.values
            .orEmpty()
            .filter { member ->
                member.id != groupSession?.selfId &&
                    member.isOnline &&
                    member.location != null
            }
            .map { member ->
                ConvoyMapMember(
                    id = member.id,
                    name = member.name,
                    position = member.location!!,
                    bearingDeg = member.bearingDeg,
                    color = member.color,
                )
            }
    } else {
        emptyList()
    }
    val realWorldMarkers = if (settings.realWorldFeelEnabled) {
        realWorldFrame.cues.map { cue ->
            RealWorldMapMarker(
                id = cue.id,
                position = cue.position,
                icon = cue.icon,
                color = cue.color,
                priority = cue.priority,
            )
        }
    } else {
        emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        NavMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MapStyleProvider.styleUrl(ConnectivityState.FULL, isNavigating = true),
            cameraPosition = mapCenter,
            zoom = 16.5,
            bearing = bearing,
            tilt = if (settings.navMapTilt) 45.0 else 0.0,
            trackCamera = true,
            routeGeometry = routeGeometry,
            routeAlternatives = previewRoutes,
            selectedRouteId = readyState?.selectedRouteId,
            routePulseColor = routePulseColor,
            cameras = routeCameraMarkers,
            convoyMembers = convoyMapMembers,
            realWorldMarkers = realWorldMarkers,
            trafficFlowTileUrls = trafficFlowTileUrls,
            onRouteTap = if (readyState != null) vm::selectRoute else null,
            vehicleType = settings.vehicleType,
            userPosition = mapCenter,
            userBearing = bearing,
        )

        RealWorldAtmosphereOverlay(
            frame = realWorldFrame,
            enabled = settings.realWorldFeelEnabled,
            ambientRoutePulseEnabled = settings.showAmbientRoutePulse,
            reduceMotion = settings.reduceMotion,
        )

        when (routingUiState) {
            is RoutingUiState.Calculating -> RoutingSpinner()
            is RoutingUiState.NoOfflineCoverage -> NoOfflineOverlay(onExit = onExit)
            is RoutingUiState.Error -> RouteErrorOverlay(
                (routingUiState as RoutingUiState.Error).message, onExit
            )
            is RoutingUiState.RouteReady -> {
                val ready = routingUiState as RoutingUiState.RouteReady
                RoutePreviewSheet(
                    state = ready,
                    onRouteSelected = vm::selectRoute,
                    onStart = { vm.startNavigation(ready.route) },
                    onCancel = onExit,
                )
            }
            else -> Unit
        }

        when (val state = navState) {
            is NavigationState.Navigating -> {
                NavigationHud(
                    progress = state.progress,
                    alerts = alerts,
                    lookaheadEvents = lookaheadEvents,
                    roadCharacters = roadCharacters,
                    borderCrossings = borderCrossings,
                    realWorldFrame = realWorldFrame,
                    groupSession = groupSession,
                    currentSpeedKph = location?.speedKph ?: 0f,
                    settings = settings,
                    onOptionsClick = { showQuickOptions = true },
                )
            }
            NavigationState.Rerouting -> ReroutingOverlay()
            NavigationState.RouteUnavailable -> NoRouteOverlay(onExit = onExit)
            NavigationState.Idle -> {
                if (routingUiState == RoutingUiState.Idle) onExit()
            }
        }

        // Quick Options overlay
        AnimatedVisibility(
            visible = showQuickOptions,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            QuickOptionsSheet(
                settings = settings,
                onDismiss = { showQuickOptions = false },
                onEndNavigation = {
                    showQuickOptions = false
                    vm.stopNavigation()
                    onExit()
                },
            )
        }
    }
}

// ─── Navigation HUD ───────────────────────────────────────────────────────────

@Composable
private fun NavigationHud(
    progress: RouteProgress,
    alerts: List<SafetyAlert>,
    lookaheadEvents: List<LookaheadEvent>,
    roadCharacters: List<RoadCharacter>,
    borderCrossings: List<BorderCrossing>,
    realWorldFrame: RealWorldFrame,
    groupSession: GroupSession?,
    currentSpeedKph: Float,
    settings: UserSettings,
    onOptionsClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        val lanePreview = if (settings.showLaneGuidance) progress.upcomingLaneGuidancePreview() else null
        ManeuverCard(progress = progress, onOptionsClick = onOptionsClick)

        lanePreview?.let { LaneGuidanceView(it) }

        if (settings.showSignboards && progress.signboard != null && progress.isApproachingManeuver) {
            SignboardView(progress.signboard!!)
        }

        CurrentDrivingContextBadges(progress = progress)

        val activeSafetyAlert = if (settings.safetyFeaturesEnabled && settings.showSpeedCameras) alerts.firstOrNull() else null
        val activeTrafficSignal = lookaheadEvents.visibleWith(settings)
            .firstOrNull { it.type == LookaheadEventType.TRAFFIC_SIGNAL }

        RealWorldCueStrip(
            realWorldFrame = realWorldFrame,
            settings = settings,
            suppressed = activeSafetyAlert != null || activeTrafficSignal != null,
        )

        Spacer(Modifier.weight(1f))

        if (settings.showBorderAlerts) borderCrossings.firstOrNull()?.let { BorderPrepBanner(it) }

        NextDrivingAlert(
            alert = activeSafetyAlert,
            trafficSignal = activeTrafficSignal,
            roadSign = null,
            units = settings.units,
        )

        HazardStrip(
            events = lookaheadEvents.visibleWith(settings),
            units = settings.units,
            suppressImmediateTypes = buildSet {
                if (activeSafetyAlert != null) add(LookaheadEventType.SPEED_CAMERA)
                if (activeTrafficSignal != null) add(LookaheadEventType.TRAFFIC_SIGNAL)
            },
        )

        if (settings.groupDriveEnabled && settings.showGroupPanel) {
            groupSession?.let { GroupEtaBar(it) }
        }

        BottomInfoBar(progress, currentSpeedKph, settings)
    }
}

@Composable
private fun RealWorldAtmosphereOverlay(
    frame: RealWorldFrame,
    enabled: Boolean,
    ambientRoutePulseEnabled: Boolean,
    reduceMotion: Boolean,
) {
    if (!enabled || frame == RealWorldFrame.Empty) return
    val atmosphere = frame.atmosphere
    val baseColor = if (ambientRoutePulseEnabled) {
        atmosphere.routePulseColor.toComposeColor()
    } else {
        "#3B82F6".toComposeColor()
    }
    val hasWind = frame.cues.any { it.type == RealWorldCueType.WIND_FLOW }
    val hasStorm = frame.cues.any { it.type == RealWorldCueType.STORM_CELL }
    val hasMoon = frame.cues.any { it.type == RealWorldCueType.MOON_NIGHT_SKY }
    val hasRouteWeather = frame.cues.any { it.type == RealWorldCueType.ROUTE_WEATHER || it.type == RealWorldCueType.FOG_DEPTH }
    val alpha = (atmosphere.intensity * 0.14f).coerceIn(0.02f, 0.10f)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Real-world ambient driving layer" },
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    baseColor.copy(alpha = alpha),
                    Color.Transparent,
                    baseColor.copy(alpha = alpha * 0.45f),
                ),
            )
        )
        if (reduceMotion) return@Canvas
        if (hasWind) {
            repeat(10) { index ->
                val x = size.width * ((index * 23 % 100) / 100f)
                val y = size.height * (0.28f + ((index * 17 % 42) / 100f))
                drawLine(
                    color = Color(0xFF7DD3FC).copy(alpha = 0.13f),
                    start = Offset(x, y),
                    end = Offset(x + size.width * 0.16f, y - 18f),
                    strokeWidth = 2.4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        if (hasStorm) {
            drawCircle(
                color = Color(0xFF2563EB).copy(alpha = 0.08f),
                radius = size.minDimension * 0.18f,
                center = Offset(size.width * 0.76f, size.height * 0.34f),
            )
        }
        if (hasMoon) {
            drawCircle(
                color = Color(0xFFE0E7FF).copy(alpha = 0.16f),
                radius = size.minDimension * 0.035f,
                center = Offset(size.width * 0.82f, size.height * 0.18f),
            )
            repeat(8) { index ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.12f),
                    radius = 1.5f,
                    center = Offset(
                        x = size.width * (0.18f + ((index * 11 % 64) / 100f)),
                        y = size.height * (0.10f + ((index * 7 % 20) / 100f)),
                    ),
                )
            }
        }
        if (hasRouteWeather && atmosphere.sky != RealWorldSky.FOG) {
            drawRect(
                color = baseColor.copy(alpha = 0.045f),
                topLeft = Offset(0f, size.height * 0.38f),
                size = Size(size.width, size.height * 0.28f),
            )
        }
        when (atmosphere.sky) {
            RealWorldSky.RAIN -> {
                repeat(18) { index ->
                    val x = size.width * ((index * 37 % 100) / 100f)
                    val y = size.height * ((index * 53 % 100) / 100f)
                    drawLine(
                        color = Color(0xFFBAE6FD).copy(alpha = 0.18f),
                        start = Offset(x, y),
                        end = Offset(x + 8f, y + 28f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            RealWorldSky.SNOW -> {
                repeat(15) { index ->
                    val x = size.width * ((index * 29 % 100) / 100f)
                    val y = size.height * ((index * 47 % 100) / 100f)
                    drawCircle(Color.White.copy(alpha = 0.20f), radius = 2.4f, center = Offset(x, y))
                }
            }
            RealWorldSky.FOG -> {
                repeat(4) { index ->
                    val y = size.height * (0.24f + index * 0.14f)
                    drawLine(
                        color = Color(0xFFE2E8F0).copy(alpha = 0.10f),
                        start = Offset(size.width * 0.08f, y),
                        end = Offset(size.width * 0.92f, y),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            RealWorldSky.SUNSET -> {
                drawCircle(
                    color = Color(0xFFF97316).copy(alpha = 0.10f),
                    radius = size.minDimension * 0.22f,
                    center = Offset(size.width * 0.78f, size.height * 0.20f),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun RealWorldCueStrip(
    realWorldFrame: RealWorldFrame,
    settings: UserSettings,
    suppressed: Boolean,
) {
    if (suppressed || !settings.realWorldFeelEnabled || realWorldFrame.cues.isEmpty()) return
    val cues = realWorldFrame.cues
        .filter { it.type != RealWorldCueType.SKY_LIGHT || settings.showSkyAndLightReality }
        .take(3)
    if (cues.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        cues.forEach { cue ->
            RealWorldCueChip(cue)
        }
    }
}

@Composable
private fun RealWorldCueChip(cue: RealWorldCue) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101827).copy(alpha = 0.78f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(cue.color.toComposeColor().copy(alpha = 0.86f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(cue.icon, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    cue.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatDistance(cue.distanceMeters),
                    color = Color(0xFFCBD5E1),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// ─── Smart Stop quick actions ─────────────────────────────────────────────────

@Composable
private fun NavQuickActions(settings: UserSettings) {
    if (!settings.smartStopsEnabled) return

    val actions = buildList {
        if (settings.showFuelButton) add(Triple("⛽", "Fuel", Color(0xFF1E3A5F)))
        if (settings.showShopButton) add(Triple("🛒", "Shop", Color(0xFF1A3A2A)))
        if (settings.showToiletButton) add(Triple("🚻", "Toilet", Color(0xFF2A2A3A)))
        if (settings.showCoffeeButton) add(Triple("☕", "Coffee", Color(0xFF3A2A1A)))
        if (settings.showEvButton) add(Triple("⚡", "Charge", Color(0xFF2A1A3A)))
    }

    if (actions.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { (emoji, label, bgColor) ->
            NavStopButton(
                emoji = emoji,
                label = label,
                bgColor = bgColor,
                modifier = Modifier.weight(1f),
                onClick = {},
            )
        }
    }
}

@Composable
private fun NavStopButton(
    emoji: String,
    label: String,
    bgColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

// ─── Quick Options sheet ──────────────────────────────────────────────────────

@Composable
private fun QuickOptionsSheet(
    settings: UserSettings,
    onDismiss: () -> Unit,
    onEndNavigation: () -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
            elevation = CardDefaults.cardElevation(16.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                // Handle
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color(0xFF3A3A5C), RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    "Navigation Options",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                // Quick option grid
                val options = listOf(
                    QuickOption("🔇", "Mute"),
                    QuickOption("🚗", "Traffic"),
                    QuickOption("📷", "Cameras"),
                    QuickOption("🛤", "Road Ahead"),
                    QuickOption("🗺", "Overview"),
                    QuickOption("🌙", "Night mode"),
                )
                val chunked = options.chunked(3)
                chunked.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { opt ->
                            QuickOptionButton(
                                emoji = opt.emoji,
                                label = opt.label,
                                modifier = Modifier.weight(1f),
                                onClick = { onDismiss() },
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF2A2A44))
                Spacer(Modifier.height(12.dp))

                if (!confirmEnd) {
                    OutlinedButton(
                        onClick = { confirmEnd = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                    ) {
                        Text("End Navigation", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        "Are you sure you want to end navigation?",
                        color = Color(0xFFEF4444),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            onClick = { confirmEnd = false },
                            modifier = Modifier.weight(1f),
                        ) { Text("Cancel", color = Color(0xFF6B7A99)) }
                        Button(
                            onClick = onEndNavigation,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        ) { Text("End", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

private data class QuickOption(val emoji: String, val label: String)

private fun String.toComposeColor(): Color =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(Color(0xFF3B82F6))

@Composable
private fun QuickOptionButton(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF2A2A44),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color(0xFFB0B8CC), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ─── Lane guidance ────────────────────────────────────────────────────────────

@Composable
private fun LaneGuidanceView(preview: LaneGuidancePreview) {
    val recommended = preview.guidance.resolvedRecommendedIndices(preview.maneuver)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF121827).copy(alpha = 0.88f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            preview.guidance.lanes.take(5).forEachIndexed { index, lane ->
                CompactLanePill(
                    lane = lane,
                    isRecommended = index in recommended,
                    fallbackDirection = preview.maneuver.preferredLaneDirections().first(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                formatDistance(preview.distanceMeters),
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactLanePill(
    lane: Lane,
    isRecommended: Boolean,
    fallbackDirection: LaneDirection,
) {
    val activeColor = Color(0xFF38BDF8)
    val inactiveColor = Color(0xFF6B7280)
    val directions = lane.directions.ifEmpty { listOf(fallbackDirection) }
    val width = if (isRecommended) 42.dp else 32.dp
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width = width, height = 52.dp)
            .background(
                if (isRecommended) activeColor.copy(alpha = 0.20f) else Color(0xFF1F2937).copy(alpha = 0.82f),
                RoundedCornerShape(18.dp),
            )
            .border(
                width = if (isRecommended) 2.dp else 1.dp,
                color = if (isRecommended) activeColor else Color(0xFF374151),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (isRecommended) {
                drawLaneDirections(directions, activeColor.copy(alpha = 0.24f), strokeWidth = 8f)
            }
            drawLaneDirections(
                directions = directions,
                color = if (isRecommended) activeColor else inactiveColor,
                strokeWidth = if (isRecommended) 5f else 3.8f,
            )
        }
    }
}

private data class LaneGuidancePreview(
    val guidance: LaneGuidance,
    val maneuver: Maneuver,
    val distanceMeters: Double,
)

private fun RouteProgress.upcomingLaneGuidancePreview(maxDistanceMeters: Double = 1_200.0): LaneGuidancePreview? {
    val steps = route.steps
    if (currentStepIndex !in steps.indices) return null
    var distance = 0.0
    for (index in currentStepIndex until steps.size) {
        val step = steps[index]
        val stepDistance = if (index == currentStepIndex) distanceToNextStepMeters else step.distanceMeters
        step.laneGuidance?.let { guidance ->
            val guidanceDistance = if (index == currentStepIndex) distanceToNextStepMeters else distance
            if (guidanceDistance <= maxDistanceMeters) {
                return LaneGuidancePreview(
                    guidance = guidance,
                    maneuver = step.maneuver,
                    distanceMeters = guidanceDistance,
                )
            }
        }
        distance += stepDistance
        if (distance > maxDistanceMeters) return null
    }
    return null
}

private fun LaneGuidance.resolvedRecommendedIndices(maneuver: Maneuver): Set<Int> {
    val explicit = recommendedIndices.filter { it in lanes.indices }.toSet()
    if (explicit.isNotEmpty()) return explicit

    val active = lanes.mapIndexedNotNull { index, lane -> index.takeIf { lane.isActive } }.toSet()
    if (active.isNotEmpty()) return active

    val targetDirections = maneuver.preferredLaneDirections()
    val inferred = lanes.mapIndexedNotNull { index, lane ->
        index.takeIf { lane.directions.any { it in targetDirections } }
    }.toSet()
    return inferred.ifEmpty { setOf((lanes.lastIndex / 2).coerceAtLeast(0)) }
}

private fun Maneuver.preferredLaneDirections(): Set<LaneDirection> = when (this) {
    Maneuver.TURN_LEFT -> setOf(LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT)
    Maneuver.TURN_SHARP_LEFT -> setOf(LaneDirection.SHARP_LEFT, LaneDirection.LEFT)
    Maneuver.TURN_SLIGHT_LEFT, Maneuver.KEEP_LEFT -> setOf(LaneDirection.SLIGHT_LEFT, LaneDirection.LEFT, LaneDirection.MERGE)
    Maneuver.TURN_RIGHT -> setOf(LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.EXIT)
    Maneuver.TURN_SHARP_RIGHT -> setOf(LaneDirection.SHARP_RIGHT, LaneDirection.RIGHT, LaneDirection.EXIT)
    Maneuver.TURN_SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> setOf(LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT, LaneDirection.EXIT, LaneDirection.MERGE)
    Maneuver.U_TURN -> setOf(LaneDirection.U_TURN)
    Maneuver.ROUNDABOUT_ENTER, Maneuver.ROUNDABOUT_EXIT -> setOf(LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT, LaneDirection.STRAIGHT)
    else -> setOf(LaneDirection.STRAIGHT)
}

private fun DrawScope.drawLaneRoadLayout(
    lanes: List<Lane>,
    recommendedIndices: Set<Int>,
    maneuver: Maneuver,
) {
    val visibleLanes = lanes.take(6).ifEmpty { listOf(Lane(listOf(LaneDirection.STRAIGHT), isActive = true)) }
    val laneCount = visibleLanes.size
    val padX = 18f
    val roadTop = 9f
    val roadBottom = size.height - 7f
    val splitY = size.height * 0.55f
    val laneWidth = (size.width - padX * 2f) / laneCount
    val active = Color(0xFF38BDF8)
    val activeGlow = active.copy(alpha = 0.25f)
    val inactive = Color(0xFF7B8499)
    val roadColor = Color(0xFF151827)

    drawRoundRect(
        color = roadColor,
        topLeft = androidx.compose.ui.geometry.Offset(padX - 9f, roadTop - 4f),
        size = androidx.compose.ui.geometry.Size(size.width - (padX - 9f) * 2f, roadBottom - roadTop + 8f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
    )

    for (index in 1 until laneCount) {
        val x = padX + laneWidth * index
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = androidx.compose.ui.geometry.Offset(x, roadBottom),
            end = androidx.compose.ui.geometry.Offset(x, splitY),
            strokeWidth = 2.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 9f)),
            cap = StrokeCap.Round,
        )
    }

    visibleLanes.forEachIndexed { index, lane ->
        val isRecommended = index in recommendedIndices
        val color = if (isRecommended) active else inactive.copy(alpha = 0.72f)
        val directions = lane.directions.ifEmpty { listOf(maneuver.preferredLaneDirections().first()) }.distinct()
        directions.forEach { direction ->
            val path = lanePanelPath(
                laneIndex = index,
                laneCount = laneCount,
                direction = direction,
                padX = padX,
                laneWidth = laneWidth,
                splitY = splitY,
                roadTop = roadTop,
                roadBottom = roadBottom,
            )
            if (isRecommended) {
                drawPath(
                    path = path,
                    color = activeGlow,
                    style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = if (isRecommended) 7.2f else 4.8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            val tip = lanePanelTip(index, laneCount, direction, padX, laneWidth, roadTop, splitY)
            drawHead(
                tip = tip,
                angleDeg = lanePanelHeadAngle(direction),
                color = color,
                strokeWidth = if (isRecommended) 6.4f else 4.4f,
            )
        }

        if (isRecommended) {
            val x = padX + laneWidth * (index + 0.5f)
            drawCircle(
                color = active,
                radius = 5.5f,
                center = androidx.compose.ui.geometry.Offset(x, roadBottom - 8f),
            )
        }
    }
}

private fun DrawScope.lanePanelPath(
    laneIndex: Int,
    laneCount: Int,
    direction: LaneDirection,
    padX: Float,
    laneWidth: Float,
    splitY: Float,
    roadTop: Float,
    roadBottom: Float,
): Path {
    val x = padX + laneWidth * (laneIndex + 0.5f)
    val leftEdge = padX + laneWidth * laneIndex
    val rightEdge = padX + laneWidth * (laneIndex + 1)
    val minX = padX + laneWidth * 0.25f
    val maxX = padX + laneWidth * (laneCount - 0.25f)
    return Path().apply {
        moveTo(x, roadBottom)
        lineTo(x, splitY)
        when (direction) {
            LaneDirection.STRAIGHT -> lineTo(x, roadTop)
            LaneDirection.SLIGHT_LEFT -> lineTo((x - laneWidth * 0.48f).coerceAtLeast(minX), roadTop)
            LaneDirection.LEFT -> cubicTo(x, splitY - 20f, leftEdge - laneWidth * 0.35f, roadTop + 25f, minX, roadTop + 11f)
            LaneDirection.SHARP_LEFT -> {
                lineTo(x, roadTop + 31f)
                lineTo(minX, roadTop + 31f)
            }
            LaneDirection.SLIGHT_RIGHT -> lineTo((x + laneWidth * 0.48f).coerceAtMost(maxX), roadTop)
            LaneDirection.RIGHT, LaneDirection.EXIT -> {
                cubicTo(x, splitY - 20f, rightEdge + laneWidth * 0.35f, roadTop + 25f, maxX, roadTop + 11f)
            }
            LaneDirection.SHARP_RIGHT -> {
                lineTo(x, roadTop + 31f)
                lineTo(maxX, roadTop + 31f)
            }
            LaneDirection.U_TURN -> {
                lineTo(x, roadTop + 30f)
                cubicTo(x, roadTop, minX, roadTop, minX, roadTop + 33f)
                lineTo(minX, splitY + 4f)
            }
            LaneDirection.MERGE -> {
                val targetX = (x + if (laneIndex < laneCount / 2f) laneWidth * 0.60f else -laneWidth * 0.60f)
                    .coerceIn(minX, maxX)
                cubicTo(x, splitY - 14f, targetX, splitY - 38f, targetX, roadTop + 7f)
            }
        }
    }
}

private fun DrawScope.lanePanelTip(
    laneIndex: Int,
    laneCount: Int,
    direction: LaneDirection,
    padX: Float,
    laneWidth: Float,
    roadTop: Float,
    splitY: Float,
): androidx.compose.ui.geometry.Offset {
    val x = padX + laneWidth * (laneIndex + 0.5f)
    val minX = padX + laneWidth * 0.25f
    val maxX = padX + laneWidth * (laneCount - 0.25f)
    return when (direction) {
        LaneDirection.STRAIGHT -> androidx.compose.ui.geometry.Offset(x, roadTop)
        LaneDirection.SLIGHT_LEFT -> androidx.compose.ui.geometry.Offset((x - laneWidth * 0.48f).coerceAtLeast(minX), roadTop)
        LaneDirection.LEFT -> androidx.compose.ui.geometry.Offset(minX, roadTop + 11f)
        LaneDirection.SHARP_LEFT -> androidx.compose.ui.geometry.Offset(minX, roadTop + 31f)
        LaneDirection.SLIGHT_RIGHT -> androidx.compose.ui.geometry.Offset((x + laneWidth * 0.48f).coerceAtMost(maxX), roadTop)
        LaneDirection.RIGHT, LaneDirection.EXIT -> androidx.compose.ui.geometry.Offset(maxX, roadTop + 11f)
        LaneDirection.SHARP_RIGHT -> androidx.compose.ui.geometry.Offset(maxX, roadTop + 31f)
        LaneDirection.U_TURN -> androidx.compose.ui.geometry.Offset(minX, splitY + 4f)
        LaneDirection.MERGE -> {
            val targetX = (x + if (laneIndex < laneCount / 2f) laneWidth * 0.60f else -laneWidth * 0.60f)
                .coerceIn(minX, maxX)
            androidx.compose.ui.geometry.Offset(targetX, roadTop + 7f)
        }
    }
}

private fun lanePanelHeadAngle(direction: LaneDirection): Double = when (direction) {
    LaneDirection.STRAIGHT -> -90.0
    LaneDirection.SLIGHT_LEFT -> -112.0
    LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> 180.0
    LaneDirection.SLIGHT_RIGHT -> -68.0
    LaneDirection.RIGHT, LaneDirection.EXIT, LaneDirection.SHARP_RIGHT -> 0.0
    LaneDirection.U_TURN -> 90.0
    LaneDirection.MERGE -> -90.0
}

@Composable
private fun LaneSign(lane: Lane, isRecommended: Boolean) {
    val activeColor = Color(0xFF38BDF8)
    val inactiveColor = Color(0xFF7B8499)
    val strokeColor = if (isRecommended) activeColor else inactiveColor
    val background = if (isRecommended) Color(0xFF0B2A45) else Color(0xFF282A3E)
    val borderColor = if (isRecommended) activeColor.copy(alpha = 0.95f) else Color(0xFF3A4056)
    val directions = lane.directions.ifEmpty { listOf(LaneDirection.STRAIGHT) }

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(width = 48.dp, height = 62.dp)
            .background(
                if (isRecommended) activeColor.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .background(background, RoundedCornerShape(10.dp))
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (isRecommended) {
                drawLaneDirections(directions, activeColor.copy(alpha = 0.24f), strokeWidth = 9f)
            }
            drawLaneDirections(directions, strokeColor, strokeWidth = if (isRecommended) 5.4f else 4.4f)
        }
    }
}

private fun DrawScope.drawLaneDirections(
    directions: List<LaneDirection>,
    color: Color,
    strokeWidth: Float,
) {
    directions.distinct().forEach { direction ->
        val path = lanePath(direction)
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawArrowHead(direction, color, strokeWidth)
    }
}

private fun DrawScope.lanePath(direction: LaneDirection): Path {
    val w = size.width
    val h = size.height
    val x = w / 2f
    val bottom = h - 2f
    val split = h * 0.42f
    val top = 3f
    return Path().apply {
        moveTo(x, bottom)
        lineTo(x, split)
        when (direction) {
            LaneDirection.LEFT -> cubicTo(x, h * 0.30f, w * 0.30f, h * 0.22f, w * 0.18f, top + 11f)
            LaneDirection.SLIGHT_LEFT -> lineTo(w * 0.30f, top + 8f)
            LaneDirection.SHARP_LEFT -> { lineTo(x, h * 0.25f); lineTo(w * 0.16f, h * 0.25f) }
            LaneDirection.RIGHT, LaneDirection.EXIT -> cubicTo(x, h * 0.30f, w * 0.70f, h * 0.22f, w * 0.82f, top + 11f)
            LaneDirection.SLIGHT_RIGHT -> lineTo(w * 0.70f, top + 8f)
            LaneDirection.SHARP_RIGHT -> { lineTo(x, h * 0.25f); lineTo(w * 0.84f, h * 0.25f) }
            LaneDirection.U_TURN -> {
                lineTo(x, h * 0.24f)
                cubicTo(x, top, w * 0.18f, top, w * 0.18f, h * 0.28f)
                lineTo(w * 0.18f, h * 0.48f)
            }
            LaneDirection.MERGE -> {
                lineTo(x, h * 0.23f)
                moveTo(w * 0.20f, bottom)
                cubicTo(w * 0.20f, h * 0.58f, w * 0.34f, h * 0.44f, x, h * 0.33f)
            }
            LaneDirection.STRAIGHT -> lineTo(x, top)
        }
    }
}

private fun DrawScope.drawArrowHead(direction: LaneDirection, color: Color, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    val angle = when (direction) {
        LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> 210.0
        LaneDirection.SLIGHT_LEFT -> 235.0
        LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT, LaneDirection.EXIT -> -30.0
        LaneDirection.SLIGHT_RIGHT -> -55.0
        LaneDirection.U_TURN -> 90.0
        LaneDirection.MERGE, LaneDirection.STRAIGHT -> -90.0
    }
    val tip = when (direction) {
        LaneDirection.LEFT -> androidx.compose.ui.geometry.Offset(w * 0.18f, 14f)
        LaneDirection.SLIGHT_LEFT -> androidx.compose.ui.geometry.Offset(w * 0.30f, 11f)
        LaneDirection.SHARP_LEFT -> androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.25f)
        LaneDirection.RIGHT, LaneDirection.EXIT -> androidx.compose.ui.geometry.Offset(w * 0.82f, 14f)
        LaneDirection.SLIGHT_RIGHT -> androidx.compose.ui.geometry.Offset(w * 0.70f, 11f)
        LaneDirection.SHARP_RIGHT -> androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.25f)
        LaneDirection.U_TURN -> androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.48f)
        LaneDirection.MERGE, LaneDirection.STRAIGHT -> androidx.compose.ui.geometry.Offset(w / 2f, 3f)
    }
    drawHead(tip, angle, color, strokeWidth)
}

private fun DrawScope.drawHead(
    tip: androidx.compose.ui.geometry.Offset,
    angleDeg: Double,
    color: Color,
    strokeWidth: Float,
) {
    val length = 10f
    val angleRad = Math.toRadians(angleDeg)
    val left = angleRad + Math.toRadians(145.0)
    val right = angleRad - Math.toRadians(145.0)
    val leftPoint = androidx.compose.ui.geometry.Offset(
        x = tip.x + cos(left).toFloat() * length,
        y = tip.y + sin(left).toFloat() * length,
    )
    val rightPoint = androidx.compose.ui.geometry.Offset(
        x = tip.x + cos(right).toFloat() * length,
        y = tip.y + sin(right).toFloat() * length,
    )
    drawLine(color, tip, leftPoint, strokeWidth, cap = StrokeCap.Round)
    drawLine(color, tip, rightPoint, strokeWidth, cap = StrokeCap.Round)
}

// ─── Driving context ─────────────────────────────────────────────────────────

@Composable
private fun CurrentDrivingContextBadges(progress: RouteProgress) {
    if (progress.speedLimitKph !in 1..30) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoadSignBadge(kind = RoadSignKind.Zone30, label = "now", distance = null)
    }
}

@Composable
private fun DrivingBadge(icon: String, label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(icon, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RoadSignBadge(kind: RoadSignKind, label: String, distance: String?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF111827).copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoadSignIcon(kind = kind, modifier = Modifier.size(38.dp))
            Column {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                distance?.let {
                    Text(it, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun JunctionBadge(title: String, distance: String, complexity: Float) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF2A233D).copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            ComplexityDots(complexity)
            Text(distance, color = Color(0xFFB0B8CC), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ComplexityDots(complexity: Float) {
    val active = when {
        complexity >= 0.70f -> 3
        complexity >= 0.38f -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 15.dp)
                    .background(
                        if (index < active) Color(0xFFF59E0B) else Color(0xFF4B5563),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

// ─── Motorway signboard ───────────────────────────────────────────────────────

@Composable
private fun SignboardView(signboard: Signboard) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1A4731),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            signboard.exitNumber?.let { exit ->
                Text(
                    "Exit $exit",
                    color = Color(0xFF1C1C2E),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(Color(0xFFFACC15), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            signboard.roadNumber?.let { num ->
                Text(
                    num,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color(0xFF1E40AF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Column {
                signboard.destinations.take(2).forEach { dest ->
                    Text(
                        dest,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ─── Maneuver card ────────────────────────────────────────────────────────────

@Composable
private fun ManeuverCard(progress: RouteProgress, onOptionsClick: () -> Unit) {
    val instruction = when {
        progress.distanceRemainingMeters <= ARRIVAL_DISTANCE_METERS -> "Arrive at destination"
        progress.nextInstruction.isBlank() -> "Continue on route"
        else -> progress.nextInstruction
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionalManeuverIcon(progress = progress, modifier = Modifier.size(76.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatManeuverDistance(progress),
                    fontSize = 38.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    instruction,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    color = Color(0xFFB0B8CC),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progress.nextStreetName?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7A99))
                }
            }
            // Options button — replaces the old close button; End Navigation is behind it
            IconButton(onClick = onOptionsClick) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Navigation options",
                    tint = Color(0xFF6B7A99),
                )
            }
        }
    }
}

private const val ARRIVAL_DISTANCE_METERS = 25.0

private fun formatManeuverDistance(progress: RouteProgress): String = when {
    progress.distanceRemainingMeters <= ARRIVAL_DISTANCE_METERS -> "Arriving"
    progress.distanceToNextStepMeters < 10.0 -> "Now"
    else -> formatDistance(progress.distanceToNextStepMeters)
}

@Composable
private fun DirectionalManeuverIcon(progress: RouteProgress, modifier: Modifier = Modifier) {
    val roundabout = progress.roundaboutDiagram()
    if (roundabout != null) {
        RoundaboutManeuverIcon(diagram = roundabout, modifier = modifier)
    } else {
        RouteShapeManeuverIcon(progress = progress, modifier = modifier)
    }
}

@Composable
private fun RouteShapeManeuverIcon(progress: RouteProgress, modifier: Modifier = Modifier) {
    val previewPoints = progress.routeAheadPreviewPoints()
    Box(
        modifier = modifier
            .background(Color(0xFF2D2D44), CircleShape)
            .semantics { contentDescription = "Route-shaped maneuver preview" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            if (previewPoints.routePreviewIsUseful(progress.nextManeuver)) {
                drawRouteShapePreview(previewPoints)
            } else {
                drawSyntheticManeuverPreview(progress.nextManeuver)
            }
        }
    }
}

private fun DrawScope.drawRouteShapePreview(points: List<LatLng>) {
    val offsets = points.toDiagramOffsets(size.width, size.height)
    if (offsets.size < 2) return
    val path = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val active = Color(0xFF38BDF8)
    drawPath(
        path = path,
        color = Color(0xFF111827),
        style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = active.copy(alpha = 0.25f),
        style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = active,
        style = Stroke(width = 6.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    val tip = offsets.last()
    val previous = offsets.dropLast(1).lastOrNull() ?: return
    val angle = Math.toDegrees(kotlin.math.atan2((tip.y - previous.y).toDouble(), (tip.x - previous.x).toDouble()))
    drawHead(tip = tip, angleDeg = angle, color = active, strokeWidth = 5.8f)
}

private fun DrawScope.drawSyntheticManeuverPreview(maneuver: Maneuver) {
    val path = syntheticManeuverPath(maneuver)
    val active = Color(0xFF38BDF8)
    drawPath(
        path = path,
        color = Color(0xFF111827),
        style = Stroke(width = 16f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = active.copy(alpha = 0.25f),
        style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = active,
        style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val tip = syntheticManeuverTip(maneuver)
    drawHead(tip = tip, angleDeg = syntheticManeuverHeadAngle(maneuver), color = active, strokeWidth = 5.8f)
}

private fun DrawScope.syntheticManeuverPath(maneuver: Maneuver): Path {
    val w = size.width
    val h = size.height
    val x = w / 2f
    val bottom = h - 3f
    val split = h * 0.55f
    val top = 4f
    return Path().apply {
        moveTo(x, bottom)
        lineTo(x, split)
        when (maneuver) {
            Maneuver.TURN_LEFT -> cubicTo(x, h * 0.38f, w * 0.26f, h * 0.26f, w * 0.18f, top + 16f)
            Maneuver.TURN_SHARP_LEFT -> {
                lineTo(x, h * 0.32f)
                lineTo(w * 0.16f, h * 0.32f)
            }
            Maneuver.TURN_SLIGHT_LEFT, Maneuver.KEEP_LEFT -> lineTo(w * 0.30f, top + 6f)
            Maneuver.TURN_RIGHT -> cubicTo(x, h * 0.38f, w * 0.74f, h * 0.26f, w * 0.82f, top + 16f)
            Maneuver.TURN_SHARP_RIGHT -> {
                lineTo(x, h * 0.32f)
                lineTo(w * 0.84f, h * 0.32f)
            }
            Maneuver.TURN_SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> lineTo(w * 0.70f, top + 6f)
            Maneuver.U_TURN -> {
                lineTo(x, h * 0.24f)
                cubicTo(x, top, w * 0.20f, top, w * 0.20f, h * 0.29f)
                lineTo(w * 0.20f, h * 0.52f)
            }
            Maneuver.ARRIVE -> lineTo(x, top + 8f)
            else -> lineTo(x, top)
        }
    }
}

private fun DrawScope.syntheticManeuverTip(maneuver: Maneuver): androidx.compose.ui.geometry.Offset {
    val w = size.width
    val h = size.height
    return when (maneuver) {
        Maneuver.TURN_LEFT -> androidx.compose.ui.geometry.Offset(w * 0.18f, 20f)
        Maneuver.TURN_SHARP_LEFT -> androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.32f)
        Maneuver.TURN_SLIGHT_LEFT, Maneuver.KEEP_LEFT -> androidx.compose.ui.geometry.Offset(w * 0.30f, 10f)
        Maneuver.TURN_RIGHT -> androidx.compose.ui.geometry.Offset(w * 0.82f, 20f)
        Maneuver.TURN_SHARP_RIGHT -> androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.32f)
        Maneuver.TURN_SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> androidx.compose.ui.geometry.Offset(w * 0.70f, 10f)
        Maneuver.U_TURN -> androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.52f)
        else -> androidx.compose.ui.geometry.Offset(w / 2f, 4f)
    }
}

private fun syntheticManeuverHeadAngle(maneuver: Maneuver): Double = when (maneuver) {
    Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT -> 180.0
    Maneuver.TURN_SLIGHT_LEFT, Maneuver.KEEP_LEFT -> -112.0
    Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT -> 0.0
    Maneuver.TURN_SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> -68.0
    Maneuver.U_TURN -> 90.0
    else -> -90.0
}

private fun RouteProgress.routeAheadPreviewPoints(): List<LatLng> {
    val geometry = route.geometry
    if (geometry.size < 2) return currentStep.geometry
    val projection = geometry.closestProjection(snappedLocation) ?: return currentStep.geometry
    val lookaheadMeters = (distanceToNextStepMeters + 110.0).coerceIn(90.0, 520.0)
    val points = mutableListOf(projection.point)
    var remaining = lookaheadMeters
    for (index in projection.segmentIndex until geometry.lastIndex) {
        val start = if (index == projection.segmentIndex) projection.point else geometry[index]
        val end = geometry[index + 1]
        val segmentDistance = start.distanceTo(end)
        if (segmentDistance <= 0.5) continue
        if (remaining <= segmentDistance) {
            points += start.interpolateTo(end, remaining / segmentDistance)
            break
        }
        points += end
        remaining -= segmentDistance
    }
    return points.compactPolyline(minDistanceMeters = 7.0)
}

private fun List<LatLng>.routePreviewIsUseful(maneuver: Maneuver): Boolean {
    if (size < 3) return maneuver == Maneuver.STRAIGHT || maneuver == Maneuver.DEPART || maneuver == Maneuver.ARRIVE
    val totalDistance = zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
    if (totalDistance < 25.0) return false
    return true
}

private data class RouteProjection(
    val point: LatLng,
    val segmentIndex: Int,
)

private fun List<LatLng>.closestProjection(point: LatLng): RouteProjection? {
    if (size < 2) return null
    var bestPoint = first()
    var bestIndex = 0
    var bestDistance = Double.MAX_VALUE
    for (index in 0 until lastIndex) {
        val projected = point.projectOntoSegment(this[index], this[index + 1])
        val distance = point.distanceTo(projected)
        if (distance < bestDistance) {
            bestDistance = distance
            bestPoint = projected
            bestIndex = index
        }
    }
    return RouteProjection(bestPoint, bestIndex)
}

private fun LatLng.projectOntoSegment(start: LatLng, end: LatLng): LatLng {
    val ax = end.lng - start.lng
    val ay = end.lat - start.lat
    val denominator = ax * ax + ay * ay
    if (denominator == 0.0) return start
    val t = ((lng - start.lng) * ax + (lat - start.lat) * ay) / denominator
    return start.interpolateTo(end, t.coerceIn(0.0, 1.0))
}

private fun LatLng.interpolateTo(end: LatLng, fraction: Double): LatLng = LatLng(
    lat = lat + (end.lat - lat) * fraction.coerceIn(0.0, 1.0),
    lng = lng + (end.lng - lng) * fraction.coerceIn(0.0, 1.0),
)

private fun List<LatLng>.compactPolyline(minDistanceMeters: Double): List<LatLng> {
    if (size <= 2) return this
    val compact = mutableListOf(first())
    drop(1).dropLast(1).forEach { point ->
        if (compact.last().distanceTo(point) >= minDistanceMeters) compact += point
    }
    if (compact.last() != last()) compact += last()
    return compact
}

private fun List<LatLng>.toDiagramOffsets(width: Float, height: Float): List<androidx.compose.ui.geometry.Offset> {
    if (size < 2) return emptyList()
    val origin = first()
    val bearingPoint = firstOrNull { origin.distanceTo(it) >= 6.0 } ?: getOrNull(1) ?: return emptyList()
    val bearing = Math.toRadians(origin.bearingTo(bearingPoint))
    val cosBearing = cos(bearing).toFloat()
    val sinBearing = sin(bearing).toFloat()
    val raw = map { point ->
        val meters = origin.relativeMeters(point)
        val rotatedX = meters.x * cosBearing - meters.y * sinBearing
        val rotatedNorth = meters.x * sinBearing + meters.y * cosBearing
        androidx.compose.ui.geometry.Offset(rotatedX, -rotatedNorth)
    }
    val minX = raw.minOf { it.x }
    val maxX = raw.maxOf { it.x }
    val minY = raw.minOf { it.y }
    val maxY = raw.maxOf { it.y }
    val rawWidth = (maxX - minX).coerceAtLeast(1f)
    val rawHeight = (maxY - minY).coerceAtLeast(1f)
    val pad = 8f
    val scale = minOf((width - pad * 2f) / rawWidth, (height - pad * 2f) / rawHeight).coerceAtMost(1.15f)
    val centerX = (minX + maxX) / 2f
    return raw.map { point ->
        androidx.compose.ui.geometry.Offset(
            x = width / 2f + (point.x - centerX) * scale,
            y = height - pad + (point.y - maxY) * scale,
        )
    }
}

private data class RelativeMeters(val x: Float, val y: Float)

private fun LatLng.relativeMeters(other: LatLng): RelativeMeters {
    val latMeters = 111_320.0
    val lngMeters = latMeters * cos(Math.toRadians(lat))
    return RelativeMeters(
        x = ((other.lng - lng) * lngMeters).toFloat(),
        y = ((other.lat - lat) * latMeters).toFloat(),
    )
}

@Composable
private fun RoundaboutManeuverIcon(diagram: RoundaboutDiagram, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF2D2D44), CircleShape)
            .semantics {
                contentDescription = diagram.exitNumber?.let { "Roundabout, take exit $it" }
                    ?: "Roundabout ahead"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(9.dp)) {
            drawRoundaboutDiagram(diagram)
        }
        diagram.exitNumber?.let { exit ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(25.dp)
                    .background(Color(0xFF38BDF8), CircleShape)
                    .border(2.dp, Color(0xFF101827), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$exit", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

private fun DrawScope.drawRoundaboutDiagram(diagram: RoundaboutDiagram) {
    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * 0.27f
    val exitLength = size.minDimension * 0.24f
    val inactive = Color(0xFF8B95AA)
    val active = Color(0xFF38BDF8)
    val glow = Color(0xFF38BDF8).copy(alpha = 0.24f)

    fun point(angleDeg: Float, extra: Float = 0f): androidx.compose.ui.geometry.Offset {
        val rad = Math.toRadians(angleDeg.toDouble())
        return androidx.compose.ui.geometry.Offset(
            x = center.x + sin(rad).toFloat() * (radius + extra),
            y = center.y - cos(rad).toFloat() * (radius + extra),
        )
    }

    drawCircle(
        color = inactive,
        radius = radius,
        center = center,
        style = Stroke(width = 4.6f, cap = StrokeCap.Round),
    )

    drawLine(
        color = inactive,
        start = point(180f, exitLength),
        end = point(180f),
        strokeWidth = 4.6f,
        cap = StrokeCap.Round,
    )

    diagram.exitAngles.forEach { angle ->
        drawLine(
            color = if (angle == diagram.selectedAngle) active.copy(alpha = 0.5f) else inactive,
            start = point(angle),
            end = point(angle, exitLength),
            strokeWidth = if (angle == diagram.selectedAngle) 6.2f else 4.6f,
            cap = StrokeCap.Round,
        )
    }

    drawLine(
        color = glow,
        start = point(180f, exitLength),
        end = point(180f),
        strokeWidth = 10.5f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = active,
        start = point(180f, exitLength),
        end = point(180f),
        strokeWidth = 6.2f,
        cap = StrokeCap.Round,
    )

    val startArc = navAngleToCanvasAngle(180f)
    val endArc = navAngleToCanvasAngle(diagram.selectedAngle)
    val sweep = -((startArc - endArc + 360f) % 360f)
    val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
    val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
    drawArc(
        color = glow,
        startAngle = startArc,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 10.5f, cap = StrokeCap.Round),
    )
    drawArc(
        color = active,
        startAngle = startArc,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 6.2f, cap = StrokeCap.Round),
    )

    drawLine(
        color = glow,
        start = point(diagram.selectedAngle),
        end = point(diagram.selectedAngle, exitLength),
        strokeWidth = 11f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = active,
        start = point(diagram.selectedAngle),
        end = point(diagram.selectedAngle, exitLength),
        strokeWidth = 6.6f,
        cap = StrokeCap.Round,
    )
    drawHead(
        tip = point(diagram.selectedAngle, exitLength),
        angleDeg = diagram.selectedAngle.toDouble() - 90.0,
        color = active,
        strokeWidth = 5.4f,
    )
}

private fun navAngleToCanvasAngle(angle: Float): Float =
    ((angle - 90f) % 360f + 360f) % 360f

private data class RoundaboutDiagram(
    val exitNumber: Int?,
    val selectedAngle: Float,
    val exitAngles: List<Float>,
)

private fun RouteProgress.roundaboutDiagram(): RoundaboutDiagram? {
    val text = nextInstruction
    val isRoundabout = nextManeuver == Maneuver.ROUNDABOUT_ENTER ||
        nextManeuver == Maneuver.ROUNDABOUT_EXIT ||
        text.contains("roundabout", ignoreCase = true) ||
        text.contains("rotary", ignoreCase = true)
    if (!isRoundabout) return null

    val exitNumber = currentStep.exitNumber?.toIntOrNull()?.coerceIn(1, 8)
        ?: text.roundaboutExitNumber()
    val geometryAngle = currentStep.geometry.roundaboutExitAngle()
    val selectedAngle = geometryAngle ?: fallbackRoundaboutExitAngle(exitNumber)
    val fallbackAngles = fallbackRoundaboutExitAngles(exitNumber)
    val exitAngles = (fallbackAngles + selectedAngle)
        .distinctBy { (it / 12f).roundToInt() }
        .sortedDescending()

    return RoundaboutDiagram(
        exitNumber = exitNumber,
        selectedAngle = selectedAngle,
        exitAngles = exitAngles,
    )
}

private fun String.roundaboutExitNumber(): Int? {
    val lower = lowercase()
    Regex("""\b(?:exit|ausfahrt)\s*(\d{1,2})\b""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it.coerceIn(1, 8)
    }
    Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+exit\b""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it.coerceIn(1, 8)
    }
    val words = listOf(
        "first" to 1,
        "second" to 2,
        "third" to 3,
        "fourth" to 4,
        "fifth" to 5,
        "sixth" to 6,
        "seventh" to 7,
        "eighth" to 8,
        "erste" to 1,
        "zweite" to 2,
        "dritte" to 3,
        "vierte" to 4,
        "funfte" to 5,
        "fünfte" to 5,
    )
    return words.firstOrNull { (word, _) -> lower.contains("$word exit") || lower.contains("$word ausfahrt") }?.second
}

private fun List<LatLng>.roundaboutExitAngle(): Float? {
    if (size < 4) return null
    val start = first()
    val entryPoint = firstOrNull { start.distanceTo(it) >= 18.0 } ?: getOrNull(1) ?: return null
    val end = last()
    val exitPoint = lastOrNull { it.distanceTo(end) >= 18.0 } ?: getOrNull(lastIndex - 1) ?: return null
    val entryBearing = start.bearingTo(entryPoint)
    val exitBearing = exitPoint.bearingTo(end)
    val relative = ((exitBearing - entryBearing + 540.0) % 360.0) - 180.0
    return relative.coerceIn(-150.0, 150.0).toFloat()
}

private fun fallbackRoundaboutExitAngle(exitNumber: Int?): Float = when (exitNumber) {
    1 -> 70f
    2 -> 8f
    3 -> -62f
    4 -> -128f
    5 -> -168f
    else -> -62f
}

private fun fallbackRoundaboutExitAngles(exitNumber: Int?): List<Float> {
    val count = (exitNumber ?: 3).coerceIn(3, 6)
    val base = listOf(72f, 8f, -62f, -128f, -168f, 128f)
    return base.take(count)
}

@Composable
private fun ManeuverIcon(maneuver: Maneuver, modifier: Modifier = Modifier) {
    val emoji = when (maneuver) {
        Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT, Maneuver.TURN_SLIGHT_LEFT -> "↰"
        Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.TURN_SLIGHT_RIGHT -> "↱"
        Maneuver.U_TURN -> "↩"
        Maneuver.ROUNDABOUT_ENTER, Maneuver.ROUNDABOUT_EXIT -> "⟳"
        Maneuver.ARRIVE -> "🏁"
        Maneuver.DEPART -> "🚀"
        Maneuver.KEEP_LEFT -> "↖"
        Maneuver.KEEP_RIGHT -> "↗"
        else -> "↑"
    }
    Box(
        modifier = modifier.background(Color(0xFF2D2D44), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 34.sp)
    }
}

// ─── Bottom info bar ──────────────────────────────────────────────────────────

@Composable
private fun BottomInfoBar(progress: RouteProgress, currentSpeedKph: Float, settings: UserSettings) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoColumn(label = "ETA", value = formatEta(progress.durationRemainingSeconds))
            SpeedDisplay(
                speedKph = currentSpeedKph,
                limitKph = if (settings.showSpeedLimit) progress.speedLimitKph else null,
                units = settings.units,
                threshold = settings.speedWarningThreshold,
            )
            InfoColumn(label = "Left", value = formatDistance(progress.distanceRemainingMeters, settings.units))
        }
    }
}

@Composable
private fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SpeedDisplay(
    speedKph: Float,
    limitKph: Int?,
    units: DistanceUnits,
    threshold: SpeedWarningThreshold,
) {
    val displaySpeed = if (units == DistanceUnits.IMPERIAL) (speedKph * 0.621371f).roundToInt()
    else speedKph.roundToInt()
    val displayLimit = if (units == DistanceUnits.IMPERIAL) limitKph?.let { (it * 0.621371f).roundToInt() }
    else limitKph
    val unitLabel = if (units == DistanceUnits.IMPERIAL) "mph" else "km/h"
    val thresholdKph = when (threshold) {
        SpeedWarningThreshold.EXACT -> 0
        SpeedWarningThreshold.PLUS_3 -> 3
        SpeedWarningThreshold.PLUS_5 -> 5
        SpeedWarningThreshold.PLUS_10 -> 10
    }
    val overLimit = displayLimit != null && displaySpeed > displayLimit + thresholdKph
    val nearLimit = displayLimit != null && displaySpeed >= displayLimit - 2
    val ringColor = when {
        overLimit -> Color(0xFFEF4444)
        nearLimit -> Color(0xFFF59E0B)
        displayLimit == null -> Color(0xFF94A3B8)
        else -> Color(0xFF22C55E)
    }
    val speedColor = if (overLimit) Color(0xFFEF4444) else Color.White
    val pulse by rememberInfiniteTransition(label = "speed-ring").animateFloat(
        initialValue = 0.42f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (overLimit) 620 else 1_600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speed-ring-alpha",
    )

    Box(
        modifier = Modifier.size(width = 102.dp, height = 114.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = Stroke(
                width = if (overLimit) 8f else 5.5f,
                cap = StrokeCap.Round,
                pathEffect = if (displayLimit == null) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
            )
            drawCircle(
                color = ringColor.copy(alpha = pulse),
                radius = size.minDimension / 2f - 6f,
                style = stroke,
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.14f),
                radius = size.minDimension / 2f - 15f,
                style = Stroke(width = 2f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (displayLimit != null) {
                SpeedLimitBadge(displayLimit)
                Spacer(Modifier.height(2.dp))
            }
            Text("$displaySpeed", color = speedColor, fontWeight = FontWeight.Bold, fontSize = 38.sp)
            Text(unitLabel, color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SpeedLimitBadge(kph: Int) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.White, CircleShape)
                .border(3.dp, Color(0xFFCC0000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$kph", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─── Next driving alert ───────────────────────────────────────────────────────

@Composable
private fun NextDrivingAlert(
    alert: SafetyAlert?,
    trafficSignal: LookaheadEvent?,
    roadSign: LookaheadEvent?,
    units: DistanceUnits,
) {
    if (alert == null && trafficSignal == null && roadSign == null) return

    val color = when (alert?.severity ?: trafficSignal?.severity?.toSeverity() ?: roadSign?.severity?.toSeverity()) {
        Severity.CRITICAL, Severity.HIGH -> Color(0xFFDC2626)
        Severity.MEDIUM -> Color(0xFFE11D48)
        else -> Color(0xFF1C1C2E)
    }.copy(alpha = 0.95f)
    val title = alert?.drivingTitle() ?: trafficSignal?.title ?: roadSign?.title.orEmpty()
    val distanceMeters = alert?.distanceMeters ?: trafficSignal?.distanceMeters ?: roadSign?.distanceMeters ?: 0.0
    val subtitle = alert?.drivingSubtitle(units) ?: trafficSignal?.subtitle ?: roadSign?.subtitle ?: "Ahead"
    val icon = alert?.drivingIcon() ?: trafficSignal?.emoji ?: roadSign?.emoji ?: "!"
    val signKind = if (alert == null && trafficSignal == null) roadSign?.roadSignKind() else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (signKind != null) {
                RoadSignIcon(kind = signKind, modifier = Modifier.size(52.dp))
            } else {
                Text(icon, fontSize = 24.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatDistance(distanceMeters, units)} · $subtitle",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            alert?.speedLimitKph?.let { limit -> SpeedLimitBadge(limit.toDisplaySpeed(units)) }
        }
    }
}

private fun SafetyAlert.drivingTitle(): String {
    val cameraType = camera?.type
    return when {
        cameraType == CameraType.RED_LIGHT -> "Red light camera"
        cameraType == CameraType.COMBINED && speedLimitKph != null -> "Speed + red light camera"
        cameraType == CameraType.COMBINED -> "Camera ahead"
        cameraType == CameraType.AVERAGE_SPEED_START ||
            cameraType == CameraType.AVERAGE_SPEED_END ||
            cameraType == CameraType.SECTION_CONTROL -> "Average speed zone"
        cameraType == CameraType.MOBILE_ZONE && speedLimitKph != null -> "Mobile camera"
        cameraType == CameraType.MOBILE_ZONE -> "Camera zone ahead"
        speedLimitKph != null -> "Speed camera"
        else -> "Camera ahead"
    }
}

private fun SafetyAlert.drivingSubtitle(units: DistanceUnits): String =
    speedLimitKph?.let { limit ->
        val unit = if (units == DistanceUnits.IMPERIAL) "mph" else "km/h"
        "Camera limit ${limit.toDisplaySpeed(units)} $unit"
    } ?: "Limit unknown"

private fun SafetyAlert.drivingIcon(): String = when (camera?.type) {
    CameraType.RED_LIGHT -> "🚦"
    CameraType.COMBINED -> "🚦"
    CameraType.AVERAGE_SPEED_START,
    CameraType.AVERAGE_SPEED_END,
    CameraType.SECTION_CONTROL -> "↔"
    CameraType.MOBILE_ZONE -> "!"
    else -> "●"
}

@Composable
private fun HazardStrip(
    events: List<LookaheadEvent>,
    units: DistanceUnits,
    suppressImmediateTypes: Set<LookaheadEventType> = emptySet(),
) {
    val upcoming = events
        .filter { it.distanceMeters in 0.0..3_000.0 }
        .filterNot { it.type in suppressImmediateTypes && it.distanceMeters <= 900.0 }
        .filter {
            it.type in setOf(
                LookaheadEventType.SPEED_CAMERA,
                LookaheadEventType.TRAFFIC_SIGNAL,
                LookaheadEventType.SPEED_LIMIT,
                LookaheadEventType.TOLL,
                LookaheadEventType.TUNNEL,
                LookaheadEventType.ROUNDABOUT,
                LookaheadEventType.JUNCTION,
                LookaheadEventType.LANE_GUIDANCE,
                LookaheadEventType.RESIDENTIAL_ZONE,
                LookaheadEventType.TRAFFIC_CALMING,
                LookaheadEventType.SCHOOL_ZONE,
                LookaheadEventType.NOISE_PROTECTION_ZONE,
                LookaheadEventType.STOP_SIGN,
                LookaheadEventType.GIVE_WAY_SIGN,
                LookaheadEventType.PRIORITY_ROAD,
                LookaheadEventType.WEATHER,
            )
        }
        .distinctBy { it.hazardDedupeKey() }
        .take(7)
    if (upcoming.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111827).copy(alpha = 0.93f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("3 km", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            upcoming.forEach { event ->
                HazardChip(event = event, units = units)
            }
        }
    }
}

@Composable
private fun HazardChip(event: LookaheadEvent, units: DistanceUnits) {
    val color = when (event.severity) {
        LookaheadSeverity.ALERT -> Color(0xFFDC2626)
        LookaheadSeverity.WARNING -> Color(0xFFF59E0B)
        LookaheadSeverity.INFO -> Color(0xFF38BDF8)
    }
    Column(
        modifier = Modifier
            .width(72.dp)
            .background(Color(0xFF1F2937), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val signKind = event.roadSignKind()
        if (signKind != null) {
            RoadSignIcon(kind = signKind, modifier = Modifier.size(30.dp))
        } else {
            Text(event.emoji, fontSize = 16.sp, maxLines = 1)
        }
        Text(
            formatDistance(event.distanceMeters, units),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            event.shortTitle(),
            color = Color(0xFFB0B8CC),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LookaheadEvent.shortTitle(): String = when (type) {
    LookaheadEventType.SPEED_CAMERA -> "Camera"
    LookaheadEventType.TRAFFIC_SIGNAL -> "Signal"
    LookaheadEventType.SPEED_LIMIT -> title.removePrefix("Speed limit ")
    LookaheadEventType.ROUNDABOUT -> "Circle"
    LookaheadEventType.LANE_GUIDANCE -> "Lane"
    LookaheadEventType.RESIDENTIAL_ZONE -> "30 zone"
    LookaheadEventType.NOISE_PROTECTION_ZONE -> "Quiet"
    LookaheadEventType.TRAFFIC_CALMING -> "Calm"
    LookaheadEventType.SCHOOL_ZONE -> "School"
    LookaheadEventType.STOP_SIGN -> "Stop"
    LookaheadEventType.GIVE_WAY_SIGN -> "Yield"
    LookaheadEventType.PRIORITY_ROAD -> "Priority"
    LookaheadEventType.JUNCTION -> "Junction"
    LookaheadEventType.TUNNEL -> "Tunnel"
    LookaheadEventType.TOLL -> "Toll"
    LookaheadEventType.WEATHER -> "Weather"
    else -> title
}

private fun LookaheadEvent.hazardDedupeKey(): String {
    val signKind = roadSignKind()
    if (signKind == RoadSignKind.Zone30 || (type == LookaheadEventType.SPEED_LIMIT && title.contains("30"))) {
        return "zone-30-${(distanceMeters / 100.0).roundToInt()}"
    }
    return signKind?.let { "sign-$it-${(distanceMeters / 100.0).roundToInt()}" }
        ?: "${type.name}-${shortTitle()}-${(distanceMeters / 100.0).roundToInt()}"
}

private fun LookaheadSeverity.toSeverity(): Severity = when (this) {
    LookaheadSeverity.ALERT -> Severity.HIGH
    LookaheadSeverity.WARNING -> Severity.MEDIUM
    LookaheadSeverity.INFO -> Severity.INFO
}

private fun Int.toDisplaySpeed(units: DistanceUnits): Int =
    if (units == DistanceUnits.IMPERIAL) (this * 0.621371f).roundToInt() else this

private fun List<LookaheadEvent>.visibleWith(settings: UserSettings): List<LookaheadEvent> =
    filter { event ->
        when (event.type) {
            LookaheadEventType.SPEED_CAMERA -> settings.safetyFeaturesEnabled && settings.showSpeedCameras
            else -> true
        }
    }

private enum class RoadSignKind {
    Zone30,
    Calming,
    School,
    Stop,
    GiveWay,
    Priority,
    Noise,
}

private data class TrafficSignSpec(
    val country: String,
    val code: String,
    val kind: RoadSignKind,
    val limit: Int? = null,
)

private object TrafficSignCatalog {
    private val germanDefaults = mapOf(
        RoadSignKind.Zone30 to TrafficSignSpec("DE", "DE:274-30", RoadSignKind.Zone30, limit = 30),
        RoadSignKind.School to TrafficSignSpec("DE", "DE:136", RoadSignKind.School),
        RoadSignKind.Calming to TrafficSignSpec("DE", "DE:325.1", RoadSignKind.Calming),
        RoadSignKind.GiveWay to TrafficSignSpec("DE", "DE:205", RoadSignKind.GiveWay),
        RoadSignKind.Stop to TrafficSignSpec("DE", "DE:206", RoadSignKind.Stop),
        RoadSignKind.Priority to TrafficSignSpec("DE", "DE:306", RoadSignKind.Priority),
        RoadSignKind.Noise to TrafficSignSpec("DE", "DE:380", RoadSignKind.Noise),
    )

    private val countryFallbackCodes = mapOf(
        "AT" to mapOf(
            RoadSignKind.Zone30 to "AT:52a-30",
            RoadSignKind.School to "AT:2",
            RoadSignKind.GiveWay to "AT:23",
            RoadSignKind.Stop to "AT:24",
            RoadSignKind.Priority to "AT:25a",
        ),
        "CH" to mapOf(
            RoadSignKind.Zone30 to "CH:2.30-30",
            RoadSignKind.School to "CH:1.23",
            RoadSignKind.GiveWay to "CH:3.02",
            RoadSignKind.Stop to "CH:3.01",
            RoadSignKind.Priority to "CH:3.03",
        ),
        "IT" to mapOf(
            RoadSignKind.Zone30 to "IT:323-30",
            RoadSignKind.School to "IT:25",
            RoadSignKind.GiveWay to "IT:36",
            RoadSignKind.Stop to "IT:37",
            RoadSignKind.Priority to "IT:35",
        ),
        "HR" to mapOf(
            RoadSignKind.Zone30 to "HR:B31-30",
            RoadSignKind.School to "HR:A33",
            RoadSignKind.GiveWay to "HR:B01",
            RoadSignKind.Stop to "HR:B02",
            RoadSignKind.Priority to "HR:B03",
        ),
    )

    fun defaultSpec(kind: RoadSignKind, country: String = "DE"): TrafficSignSpec {
        if (country == "DE") return germanDefaults[kind] ?: TrafficSignSpec("DE", "DE:unknown", kind)
        val code = countryFallbackCodes[country]?.get(kind)
        return TrafficSignSpec(country, code ?: "$country:unknown", kind, limit = if (kind == RoadSignKind.Zone30) 30 else null)
    }
}

private fun LookaheadEvent.roadSignKind(): RoadSignKind? = when (type) {
    LookaheadEventType.RESIDENTIAL_ZONE -> RoadSignKind.Zone30
    LookaheadEventType.TRAFFIC_CALMING -> RoadSignKind.Calming
    LookaheadEventType.SCHOOL_ZONE -> RoadSignKind.School
    LookaheadEventType.STOP_SIGN -> RoadSignKind.Stop
    LookaheadEventType.GIVE_WAY_SIGN -> RoadSignKind.GiveWay
    LookaheadEventType.PRIORITY_ROAD -> RoadSignKind.Priority
    LookaheadEventType.NOISE_PROTECTION_ZONE -> RoadSignKind.Noise
    else -> null
}

@Composable
private fun RoadSignIcon(kind: RoadSignKind, modifier: Modifier = Modifier) {
    val spec = TrafficSignCatalog.defaultSpec(kind)
    Box(
        modifier = modifier.semantics { contentDescription = spec.accessibilityLabel() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoadSign(spec)
        }
        when (kind) {
            RoadSignKind.Zone30 -> Text("${spec.limit ?: 30}", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            RoadSignKind.Stop -> Text("STOP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 8.sp, maxLines = 1)
            else -> Unit
        }
    }
}

private fun TrafficSignSpec.accessibilityLabel(): String = when (kind) {
    RoadSignKind.Zone30 -> "$code speed limit ${limit ?: 30} sign"
    RoadSignKind.Calming -> "$code traffic calming area sign"
    RoadSignKind.School -> "$code school children warning sign"
    RoadSignKind.Stop -> "$code stop sign"
    RoadSignKind.GiveWay -> "$code give way sign"
    RoadSignKind.Priority -> "$code priority road sign"
    RoadSignKind.Noise -> "$code noise protection sign"
}

private fun DrawScope.drawRoadSign(spec: TrafficSignSpec) {
    when (spec.kind) {
        RoadSignKind.Zone30 -> drawSpeedLimitRoadSign(spec.limit ?: 30)
        RoadSignKind.Calming -> drawGermanTrafficCalmingSign()
        RoadSignKind.School -> drawWarningTriangle { drawGermanSchoolChildrenSymbol() }
        RoadSignKind.Stop -> drawStopRoadSign()
        RoadSignKind.GiveWay -> drawGiveWayRoadSign()
        RoadSignKind.Priority -> drawPriorityRoadSign()
        RoadSignKind.Noise -> drawNoiseRoadSign()
    }
}

private fun DrawScope.drawSpeedLimitRoadSign(limit: Int) {
    val radius = size.minDimension * 0.46f
    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
    drawCircle(Color.White, radius = radius, center = center)
    drawCircle(Color(0xFFD40000), radius = radius, center = center, style = Stroke(width = size.minDimension * 0.11f))
    drawCircle(Color.White, radius = radius * 0.72f, center = center)
}

private fun DrawScope.drawWarningTriangle(symbol: DrawScope.() -> Unit) {
    val w = size.width
    val h = size.height
    val triangle = Path().apply {
        moveTo(w * 0.50f, h * 0.07f)
        lineTo(w * 0.94f, h * 0.86f)
        lineTo(w * 0.06f, h * 0.86f)
        close()
    }
    drawPath(triangle, Color.White)
    drawPath(triangle, Color(0xFFD40000), style = Stroke(width = size.minDimension * 0.10f, join = StrokeJoin.Round))
    symbol()
}

private fun DrawScope.drawGermanTrafficCalmingSign() {
    val blue = Color(0xFF0052B4)
    val white = Color.White
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = blue,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.04f, h * 0.04f),
        size = androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.92f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, h * 0.06f),
    )
    drawRoundRect(
        color = white,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.09f, h * 0.09f),
        size = androidx.compose.ui.geometry.Size(w * 0.82f, h * 0.82f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.025f, h * 0.025f),
        style = Stroke(width = size.minDimension * 0.035f),
    )

    val house = Path().apply {
        moveTo(w * 0.14f, h * 0.34f)
        lineTo(w * 0.28f, h * 0.20f)
        lineTo(w * 0.42f, h * 0.34f)
        lineTo(w * 0.42f, h * 0.54f)
        lineTo(w * 0.14f, h * 0.54f)
        close()
    }
    drawPath(house, white)
    drawRect(blue, topLeft = androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.40f), size = androidx.compose.ui.geometry.Size(w * 0.07f, h * 0.14f))

    drawCircle(white, radius = size.minDimension * 0.045f, center = androidx.compose.ui.geometry.Offset(w * 0.61f, h * 0.25f))
    drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.61f, h * 0.31f), androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.48f), strokeWidth = size.minDimension * 0.045f, cap = StrokeCap.Round)
    drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.42f), strokeWidth = size.minDimension * 0.036f, cap = StrokeCap.Round)
    drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.48f), androidx.compose.ui.geometry.Offset(w * 0.47f, h * 0.66f), strokeWidth = size.minDimension * 0.04f, cap = StrokeCap.Round)
    drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.48f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.66f), strokeWidth = size.minDimension * 0.04f, cap = StrokeCap.Round)
    drawCircle(white, radius = size.minDimension * 0.042f, center = androidx.compose.ui.geometry.Offset(w * 0.77f, h * 0.58f))

    val car = Path().apply {
        moveTo(w * 0.22f, h * 0.70f)
        lineTo(w * 0.30f, h * 0.59f)
        lineTo(w * 0.52f, h * 0.59f)
        lineTo(w * 0.62f, h * 0.70f)
        close()
    }
    drawPath(car, white)
    drawCircle(blue, radius = size.minDimension * 0.035f, center = androidx.compose.ui.geometry.Offset(w * 0.31f, h * 0.72f))
    drawCircle(blue, radius = size.minDimension * 0.035f, center = androidx.compose.ui.geometry.Offset(w * 0.53f, h * 0.72f))
}

private fun DrawScope.drawGermanSchoolChildrenSymbol() {
    val black = Color(0xFF111111)
    drawCircle(black, radius = size.minDimension * 0.055f, center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f))
    drawLine(black, androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.49f), androidx.compose.ui.geometry.Offset(size.width * 0.36f, size.height * 0.67f), strokeWidth = size.minDimension * 0.055f, cap = StrokeCap.Round)
    drawLine(black, androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.52f), androidx.compose.ui.geometry.Offset(size.width * 0.58f, size.height * 0.58f), strokeWidth = size.minDimension * 0.045f, cap = StrokeCap.Round)
    drawCircle(black, radius = size.minDimension * 0.045f, center = androidx.compose.ui.geometry.Offset(size.width * 0.60f, size.height * 0.46f))
    drawLine(black, androidx.compose.ui.geometry.Offset(size.width * 0.60f, size.height * 0.52f), androidx.compose.ui.geometry.Offset(size.width * 0.67f, size.height * 0.69f), strokeWidth = size.minDimension * 0.047f, cap = StrokeCap.Round)
}

private fun DrawScope.drawStopRoadSign() {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = size.minDimension * 0.46f
    val octagon = Path()
    for (i in 0 until 8) {
        val angle = Math.toRadians(22.5 + i * 45.0)
        val point = androidx.compose.ui.geometry.Offset(
            x = centerX + cos(angle).toFloat() * radius,
            y = centerY + sin(angle).toFloat() * radius,
        )
        if (i == 0) octagon.moveTo(point.x, point.y) else octagon.lineTo(point.x, point.y)
    }
    octagon.close()
    drawPath(octagon, Color(0xFFD40000))
    drawPath(octagon, Color.White, style = Stroke(width = size.minDimension * 0.055f, join = StrokeJoin.Round))
}

private fun DrawScope.drawGiveWayRoadSign() {
    val w = size.width
    val h = size.height
    val outer = Path().apply {
        moveTo(w * 0.50f, h * 0.92f)
        lineTo(w * 0.08f, h * 0.16f)
        lineTo(w * 0.92f, h * 0.16f)
        close()
    }
    val inner = Path().apply {
        moveTo(w * 0.50f, h * 0.73f)
        lineTo(w * 0.25f, h * 0.28f)
        lineTo(w * 0.75f, h * 0.28f)
        close()
    }
    drawPath(outer, Color(0xFFD40000))
    drawPath(inner, Color.White)
}

private fun DrawScope.drawPriorityRoadSign() {
    val w = size.width
    val h = size.height
    val outer = Path().apply {
        moveTo(w * 0.50f, h * 0.04f)
        lineTo(w * 0.96f, h * 0.50f)
        lineTo(w * 0.50f, h * 0.96f)
        lineTo(w * 0.04f, h * 0.50f)
        close()
    }
    val inner = Path().apply {
        moveTo(w * 0.50f, h * 0.18f)
        lineTo(w * 0.82f, h * 0.50f)
        lineTo(w * 0.50f, h * 0.82f)
        lineTo(w * 0.18f, h * 0.50f)
        close()
    }
    drawPath(outer, Color.White)
    drawPath(outer, Color(0xFF111111), style = Stroke(width = size.minDimension * 0.035f, join = StrokeJoin.Round))
    drawPath(inner, Color(0xFFFFD500))
}

private fun DrawScope.drawNoiseRoadSign() {
    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
    drawCircle(Color(0xFF1D4ED8), radius = size.minDimension * 0.44f, center = center)
    val white = Color.White
    drawLine(white, androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.58f), androidx.compose.ui.geometry.Offset(size.width * 0.44f, size.height * 0.47f), strokeWidth = size.minDimension * 0.06f, cap = StrokeCap.Round)
    drawLine(white, androidx.compose.ui.geometry.Offset(size.width * 0.44f, size.height * 0.47f), androidx.compose.ui.geometry.Offset(size.width * 0.44f, size.height * 0.68f), strokeWidth = size.minDimension * 0.06f, cap = StrokeCap.Round)
    drawArc(white, startAngle = -35f, sweepAngle = 70f, useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.47f, size.height * 0.36f), size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.28f), style = Stroke(width = size.minDimension * 0.045f, cap = StrokeCap.Round))
}

// ─── Border prep ──────────────────────────────────────────────────────────────

@Composable
private fun BorderPrepBanner(crossing: BorderCrossing) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C1C2E).copy(alpha = 0.95f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(crossing.toCountry.flag, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${crossing.toCountry.name} in ${formatDistance(crossing.distanceMeters)}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Max ${crossing.toCountry.maxSpeedKph} km/h · Motorway ${crossing.toCountry.motorwaySpeedKph} km/h",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (crossing.toCountry.requiresVignette) {
                Spacer(Modifier.height(4.dp))
                Text("⚠ ${crossing.toCountry.vignetteNote}", color = Color(0xFFF59E0B), style = MaterialTheme.typography.labelSmall)
            }
            crossing.toCountry.fuelNote?.let {
                Text("⛽ $it", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─── Group ETA bar ────────────────────────────────────────────────────────────

@Composable
private fun GroupEtaBar(session: GroupSession) {
    if (session.members.size < 2) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C1C2E).copy(alpha = 0.92f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            session.sortedMembers.take(4).forEach { member ->
                val isSelf = member.id == session.selfId
                val memberName = member.name.take(if (member.hasDeviated) 5 else 6)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (member.hasDeviated) "$memberName ↩" else memberName,
                        color = when {
                            isSelf -> Color.White
                            member.hasDeviated -> Color(0xFFF59E0B)
                            else -> Color(0xFF94A3B8)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelf || member.hasDeviated) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        member.etaSec?.let { formatEta(it) } ?: "–",
                        color = when {
                            isSelf -> Color(0xFF3B82F6)
                            member.hasDeviated -> Color(0xFFF59E0B)
                            else -> Color(0xFFB0B8CC)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            session.groupEtaSec?.let { eta ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("Group", color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelSmall)
                    Text(formatEta(eta), color = Color(0xFFF59E0B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Overlays ─────────────────────────────────────────────────────────────────

@Composable
private fun RoutePreviewSheet(
    state: RoutingUiState.RouteReady,
    onRouteSelected: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val selected = state.selectedChoice
    Box(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    state.destinationName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatDistance(state.distanceMeters),
                        color = Color(0xFF3B82F6),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("  ·  ", color = Color(0xFF6B7A99), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatEta(state.durationSeconds),
                        color = Color(0xFF6B7A99),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (selected.route.trafficDelaySeconds > 0) {
                        Text("  ·  ", color = Color(0xFF6B7A99), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            selected.badge,
                            color = Color(0xFFF59E0B),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.choices.forEach { choice ->
                        RouteChoiceCard(
                            choice = choice,
                            selected = choice.route.id == state.selectedRouteId,
                            onClick = { onRouteSelected(choice.route.id) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                RoutePreviewTimeline(route = selected.route)
                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = Color(0xFF6B7A99))
                    }
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    ) {
                        Text("Start ${selected.kind.title}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteChoiceCard(
    choice: RouteChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) Color(0xFF3B82F6) else Color(0xFF334155)
    val background = if (selected) Color(0xFF10233F) else Color(0xFF111827)
    Column(
        modifier = Modifier
            .width(178.dp)
            .height(126.dp)
            .background(background, RoundedCornerShape(14.dp))
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                choice.kind.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                choice.badge,
                color = if (choice.badge.contains("traffic")) Color(0xFFF59E0B) else Color(0xFF22C55E),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatEta(choice.route.durationSeconds), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(formatDistance(choice.route.distanceMeters), color = Color(0xFF94A3B8), fontSize = 13.sp)
        }
        Text(
            choice.detail,
            color = Color(0xFFB0B8CC),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoutePreviewTimeline(route: Route) {
    val nodes = route.previewNodes()
    if (nodes.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111827),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preview", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            nodes.forEach { node ->
                TimelineNode(node)
            }
        }
    }
}

@Composable
private fun TimelineNode(node: PreviewNode) {
    Row(
        modifier = Modifier
            .background(Color(0xFF1F2937), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(node.icon, fontSize = 14.sp)
        Text(node.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

private data class PreviewNode(val icon: String, val label: String)

private fun Route.previewNodes(): List<PreviewNode> {
    val nodes = mutableListOf<PreviewNode>()
    if (trafficDelaySeconds >= 180) nodes += PreviewNode("traffic", "+${trafficDelaySeconds / 60}m")
    if (hasTolls) nodes += PreviewNode("€", "Toll")
    steps.firstOrNull { it.speedLimitKph != null }?.speedLimitKph?.let {
        nodes += PreviewNode("$it", "limit")
    }
    steps.asSequence()
        .filter { it.maneuver != Maneuver.STRAIGHT && it.maneuver != Maneuver.DEPART }
        .take(5)
        .forEach { step ->
            nodes += PreviewNode(step.maneuver.previewIcon(), step.streetName ?: step.maneuver.previewLabel())
        }
    if (nodes.isEmpty() || nodes.all { it.label in setOf("+${trafficDelaySeconds / 60}m", "Toll", "limit") }) {
        val estimatedTurns = estimatedGeometryTurns()
        nodes += if (estimatedTurns > 0) PreviewNode("↱", "$estimatedTurns turns") else PreviewNode("↑", "Direct")
    }
    return nodes.take(8)
}

private fun Route.estimatedGeometryTurns(): Int {
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

private fun angleDifference(a: Double, b: Double): Double {
    val diff = ((b - a) % 360.0 + 540.0) % 360.0 - 180.0
    return kotlin.math.abs(diff)
}

private fun Maneuver.previewIcon(): String = when (this) {
    Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT, Maneuver.TURN_SLIGHT_LEFT -> "↰"
    Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.TURN_SLIGHT_RIGHT -> "↱"
    Maneuver.ROUNDABOUT_ENTER, Maneuver.ROUNDABOUT_EXIT -> "⟳"
    Maneuver.ON_RAMP, Maneuver.OFF_RAMP, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> "⇄"
    Maneuver.FORK_LEFT, Maneuver.FORK_RIGHT -> "⑂"
    Maneuver.U_TURN -> "↩"
    Maneuver.ARRIVE -> "✓"
    else -> "↑"
}

private fun Maneuver.previewLabel(): String = when (this) {
    Maneuver.ROUNDABOUT_ENTER, Maneuver.ROUNDABOUT_EXIT -> "Roundabout"
    Maneuver.ON_RAMP, Maneuver.OFF_RAMP -> "Ramp"
    Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> "Merge"
    Maneuver.FORK_LEFT, Maneuver.FORK_RIGHT -> "Fork"
    Maneuver.ARRIVE -> "Arrive"
    else -> "Turn"
}

@Composable
private fun RoutingSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color(0xFF3B82F6))
                Spacer(Modifier.height(12.dp))
                Text("Calculating route…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReroutingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        ) {
            Text(
                "Recalculating route…",
                color = Color.White,
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun NoRouteOverlay(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Route unavailable offline", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Download the region or reconnect to continue.",
                    color = Color(0xFF6B7A99),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onExit) { Text("Back", color = Color(0xFF3B82F6)) }
            }
        }
    }
}

@Composable
private fun NoOfflineOverlay(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📥", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("Region not downloaded", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Download the region for offline routing, or check your connection.",
                    color = Color(0xFF6B7A99),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onExit) { Text("Back", color = Color(0xFF3B82F6)) }
            }
        }
    }
}

@Composable
private fun RouteErrorOverlay(message: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠️", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("Route unavailable", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(message, color = Color(0xFF6B7A99), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onExit) { Text("Back", color = Color(0xFF3B82F6)) }
            }
        }
    }
}

// ─── Formatters ───────────────────────────────────────────────────────────────

private fun formatDistance(meters: Double, units: DistanceUnits = DistanceUnits.METRIC): String = when {
    units == DistanceUnits.IMPERIAL -> {
        val feet = meters * 3.28084
        if (feet >= 5280) "${"%.1f".format(feet / 5280)} mi" else "${feet.toInt()} ft"
    }
    meters >= 1_000 -> "${"%.1f".format(meters / 1_000)} km"
    else -> "${meters.toInt()} m"
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
