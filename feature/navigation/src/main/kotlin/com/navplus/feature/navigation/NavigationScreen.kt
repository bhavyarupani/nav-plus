package com.navplus.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.common.model.Lane
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Signboard
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.group.model.GroupSession
import com.navplus.core.map.MapStyleProvider
import com.navplus.core.map.NavMapView
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.NavigationState
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.RoadCharacterAnalyzer
import com.navplus.core.navigation.RouteProgress
import com.navplus.core.regions.BorderCrossing
import com.navplus.core.safety.model.SafetyAlert
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.navplus.core.common.model.LatLng
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.UserSettings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentHeight

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
    val groupSession by vm.groupSession.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Keep screen on / release when leaving
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val mapCenter = location?.latLng ?: LatLng(48.1351, 11.5820)
    val bearing = location?.bearingDeg ?: 0f

    // Show route on map both during preview and active navigation.
    val navigatingState = navState as? NavigationState.Navigating
    val readyState = routingUiState as? RoutingUiState.RouteReady
    val routeGeometry: List<LatLng>? =
        navigatingState?.progress?.route?.geometry ?: readyState?.route?.geometry

    Box(Modifier.fillMaxSize()) {
        NavMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MapStyleProvider.styleUrl(ConnectivityState.FULL, isNavigating = true),
            cameraPosition = mapCenter,
            zoom = 16.5,
            bearing = bearing,
            tilt = if (settings.navMapTilt) 45.0 else 0.0,
            trackCamera = true,          // instant move — no competing animations while navigating
            routeGeometry = routeGeometry,
            // Same marker the home map uses. The camera is already rotated to the
            // heading, so the icon reads as pointing up-screen along the route.
            vehicleType = settings.vehicleType,
            userPosition = location?.latLng,
            userBearing = bearing,
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
                    groupSession = groupSession,
                    currentSpeedKph = location?.speedKph ?: 0f,
                    settings = settings,
                    onExit = {
                        vm.stopNavigation()
                        onExit()
                    },
                )
            }
            NavigationState.Rerouting -> ReroutingOverlay()
            NavigationState.RouteUnavailable -> NoRouteOverlay(onExit = onExit)
            NavigationState.Idle -> {
                if (routingUiState == RoutingUiState.Idle) onExit()
            }
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
    groupSession: GroupSession?,
    currentSpeedKph: Float,
    settings: UserSettings,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ManeuverCard(progress, onExit)

        if (settings.showLaneGuidance && progress.hasLaneGuidance && progress.isApproachingManeuver) {
            progress.laneGuidance?.let { LaneGuidanceView(it) }
        }

        if (settings.showSignboards && progress.signboard != null && progress.isApproachingManeuver) {
            SignboardView(progress.signboard!!)
        }

        LookaheadTimeline(
            events = if (settings.showSpeedCameras) lookaheadEvents else emptyList(),
            roadCharacters = if (settings.showRoadPersonality) roadCharacters else emptyList(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        if (settings.showBorderAlerts) borderCrossings.firstOrNull()?.let { BorderPrepBanner(it) }
        if (settings.showSpeedCameras) alerts.firstOrNull()?.let { SafetyAlertBanner(it) }
        groupSession?.let { GroupEtaBar(it) }
        BottomInfoBar(progress, currentSpeedKph, settings)
    }
}

// ─── Lane guidance ────────────────────────────────────────────────────────────

@Composable
private fun LaneGuidanceView(guidance: LaneGuidance) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E).copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            guidance.lanes.forEachIndexed { idx, lane ->
                LaneArrow(lane = lane, isRecommended = idx in guidance.recommendedIndices)
            }
        }
    }
}

@Composable
private fun LaneArrow(lane: Lane, isRecommended: Boolean) {
    val tint = if (isRecommended) Color(0xFF3B82F6) else Color(0xFF6B7A99)
    val bg   = if (isRecommended) Color(0xFF1E3A5F) else Color(0xFF2D2D44)
    val arrow = when (lane.directions.firstOrNull() ?: LaneDirection.STRAIGHT) {
        LaneDirection.LEFT        -> "←"
        LaneDirection.SLIGHT_LEFT -> "↖"
        LaneDirection.SHARP_LEFT  -> "↰"
        LaneDirection.RIGHT       -> "→"
        LaneDirection.SLIGHT_RIGHT -> "↗"
        LaneDirection.SHARP_RIGHT  -> "↱"
        LaneDirection.U_TURN       -> "↩"
        LaneDirection.MERGE        -> "⇒"
        LaneDirection.EXIT         -> "⬆"
        else                       -> "↑"
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width = 40.dp, height = 52.dp)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            arrow,
            color = tint,
            fontSize = 24.sp,
            fontWeight = if (isRecommended) FontWeight.Bold else FontWeight.Normal,
        )
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
private fun ManeuverCard(progress: RouteProgress, onExit: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManeuverIcon(progress.nextManeuver, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatDistance(progress.distanceToNextStepMeters),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    progress.nextInstruction,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFB0B8CC),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progress.nextStreetName?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7A99))
                }
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Rounded.Close, contentDescription = "Exit navigation", tint = Color(0xFF6B7A99))
            }
        }
    }
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
        Text(emoji, fontSize = 28.sp)
    }
}

// ─── Bottom info bar ──────────────────────────────────────────────────────────

@Composable
private fun BottomInfoBar(progress: RouteProgress, currentSpeedKph: Float, settings: UserSettings) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoColumn(label = "ETA", value = formatEta(progress.durationRemainingSeconds))
            InfoColumn(label = "Distance", value = formatDistance(progress.distanceRemainingMeters, settings.units))
            SpeedDisplay(
                speedKph = currentSpeedKph,
                limitKph = if (settings.showSpeedLimit) progress.speedLimitKph else null,
                units = settings.units,
            )
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
private fun SpeedDisplay(speedKph: Float, limitKph: Int?, units: DistanceUnits) {
    val displaySpeed = if (units == DistanceUnits.IMPERIAL) (speedKph * 0.621371).toInt() else speedKph.toInt()
    val displayLimit = if (units == DistanceUnits.IMPERIAL) limitKph?.let { (it * 0.621371).toInt() } else limitKph
    val unitLabel = if (units == DistanceUnits.IMPERIAL) "mph" else "km/h"
    val overLimit = displayLimit != null && displaySpeed > displayLimit + 3
    val color = if (overLimit) Color(0xFFEF4444) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$displaySpeed",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(unitLabel, color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelSmall)
        displayLimit?.let {
            Text("limit $it", color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Safety alert ─────────────────────────────────────────────────────────────

@Composable
private fun SafetyAlertBanner(alert: SafetyAlert) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFF6B00).copy(alpha = 0.95f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📷", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(alert.title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatDistance(alert.distanceMeters),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            alert.speedLimitKph?.let { limit ->
                Spacer(Modifier.weight(1f))
                SpeedLimitSign(limit)
            }
        }
    }
}

@Composable
private fun SpeedLimitSign(kph: Int) {
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
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFCC0000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$kph", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        member.name.take(6),
                        color = if (isSelf) Color.White else Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelf) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        member.etaSec?.let { formatEta(it) } ?: "–",
                        color = if (isSelf) Color(0xFF3B82F6) else Color(0xFFB0B8CC),
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
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
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
                }
                Spacer(Modifier.height(16.dp))
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
                        Text("Start Navigation", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
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
    else            -> "${meters.toInt()} m"
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
