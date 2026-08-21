package com.navplus.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.map.MapStyleProvider
import com.navplus.core.map.NavMapView
import com.navplus.core.common.model.LatLng
import com.navplus.core.safety.model.CameraType
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.UserSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearchTap: () -> Unit,
    onGroupTap: () -> Unit = {},
    onRegionsTap: () -> Unit = {},
    onSettingsTap: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val connectivity by vm.connectivityState.collectAsStateWithLifecycle()
    val userLocation by vm.userLocation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nearbyCamera by vm.nearbyCamera.collectAsStateWithLifecycle()
    val cameraMarkers by vm.visibleCameraMarkers.collectAsStateWithLifecycle()

    // Only center camera on first GPS fix; LocationComponent moves the blue dot for subsequent updates.
    val mapCenter = remember(userLocation != null) {
        userLocation?.latLng ?: LatLng(48.1351, 11.5820)
    }

    Box(Modifier.fillMaxSize()) {
        NavMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MapStyleProvider.styleUrl(connectivity, isNavigating = false),
            cameraPosition = mapCenter,
            zoom = 14.0,
            cameras = cameraMarkers,
            onCameraIdle = vm::onViewportChanged,
            vehicleType = settings.vehicleType,
            userPosition = userLocation?.latLng,
            userBearing = userLocation?.bearingDeg ?: 0f,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchBar(onClick = onSearchTap, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettingsTap) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (connectivity != ConnectivityState.FULL) {
                ConnectivityBanner(connectivity)
            }
        }

        // Free drive HUD — speed + camera warning
        FreeDriveHud(
            speedKph = userLocation?.speedKph,
            nearbyCamera = nearbyCamera,
            settings = settings,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 120.dp),
        )

        QuickActions(
            onGroupTap = onGroupTap,
            onRegionsTap = onRegionsTap,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        )
    }
}

@Composable
private fun FreeDriveHud(
    speedKph: Float?,
    nearbyCamera: NearbyCamera?,
    settings: UserSettings,
    modifier: Modifier = Modifier,
) {
    val showSpeed = settings.showSpeedLimit && speedKph != null
    val showCamera = settings.showSpeedCameras && nearbyCamera != null

    if (!showSpeed && !showCamera) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCamera && nearbyCamera != null) {
            CameraWarningChip(camera = nearbyCamera, units = settings.units)
        }
        if (showSpeed && speedKph != null) {
            SpeedChip(speedKph = speedKph, units = settings.units)
        }
    }
}

@Composable
private fun SpeedChip(speedKph: Float, units: DistanceUnits) {
    val (speed, unit) = if (units == DistanceUnits.METRIC) {
        speedKph.roundToInt() to "km/h"
    } else {
        (speedKph * 0.621371f).roundToInt() to "mph"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCC1E293B),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$speed",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = unit,
                fontSize = 11.sp,
                color = Color(0xFFCBD5E1),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraWarningChip(camera: NearbyCamera, units: DistanceUnits) {
    val distText = if (units == DistanceUnits.METRIC) {
        if (camera.distanceMeters < 1000) "${camera.distanceMeters.roundToInt()} m"
        else "${"%.1f".format(camera.distanceMeters / 1000)} km"
    } else {
        val feet = camera.distanceMeters * 3.28084
        if (feet < 1000) "${feet.roundToInt()} ft"
        else "${"%.1f".format(feet / 5280)} mi"
    }
    val emoji = when (camera.type) {
        CameraType.RED_LIGHT -> "🚦"
        CameraType.AVERAGE_SPEED_START, CameraType.AVERAGE_SPEED_END, CameraType.SECTION_CONTROL -> "📏"
        CameraType.MOBILE_ZONE -> "🚔"
        else -> "📷"
    }
    val limitText = camera.speedLimitKph?.let {
        if (units == DistanceUnits.METRIC) " · ${it} km/h"
        else " · ${(it * 0.621371).roundToInt()} mph"
    } ?: ""
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCCDC2626),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = distText + limitText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = cameraLabel(camera.type),
                    fontSize = 11.sp,
                    color = Color(0xFFFFCDD2),
                )
            }
        }
    }
}

private fun cameraLabel(type: CameraType) = when (type) {
    CameraType.FIXED_SPEED -> "Speed camera"
    CameraType.RED_LIGHT -> "Red light camera"
    CameraType.COMBINED -> "Combined camera"
    CameraType.AVERAGE_SPEED_START -> "Avg speed start"
    CameraType.AVERAGE_SPEED_END -> "Avg speed end"
    CameraType.MOBILE_ZONE -> "Mobile zone"
    CameraType.SECTION_CONTROL -> "Section control"
}

@Composable
private fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Where to?", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectivityBanner(state: ConnectivityState) {
    val (bg, text) = when (state) {
        ConnectivityState.LIMITED -> Color(0xFFF59E0B) to "Weak signal — limited online features"
        ConnectivityState.OFFLINE -> Color(0xFFEF4444) to "Offline — navigation continues locally"
        ConnectivityState.FULL    -> return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = bg.copy(alpha = 0.9f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun QuickActions(onGroupTap: () -> Unit, onRegionsTap: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QuickActionButton(emoji = "⛽", label = "Fuel")
            QuickActionButton(emoji = "🛒", label = "Shop")
            QuickActionButton(emoji = "🚻", label = "Toilet")
            QuickActionButton(emoji = "☕", label = "Break")
            QuickActionButton(emoji = "⚡", label = "Charge")
            QuickActionButton(emoji = "👥", label = "Group", onClick = onGroupTap)
            QuickActionButton(emoji = "📥", label = "Regions", onClick = onRegionsTap)
        }
    }
}

@Composable
private fun QuickActionButton(emoji: String, label: String, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
