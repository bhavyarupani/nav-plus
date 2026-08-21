package com.navplus.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.navplus.core.map.CameraMarker
import com.navplus.core.map.MapStyleProvider
import com.navplus.core.map.NavMapView
import com.navplus.core.common.model.LatLng
import com.navplus.core.safety.model.CameraType
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.UserSettings
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearchTap: () -> Unit,
    onNavigateTo: (LatLng, String) -> Unit = { _, _ -> },
    onSetHome: () -> Unit = {},
    onSetWork: () -> Unit = {},
    onSettingsTap: () -> Unit = {},
    onTripsTap: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val connectivity by vm.connectivityState.collectAsStateWithLifecycle()
    val userLocation by vm.userLocation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nearbyCamera by vm.nearbyCamera.collectAsStateWithLifecycle()
    val cameraMarkers by vm.visibleCameraMarkers.collectAsStateWithLifecycle()
    val selectedCamera by vm.selectedCamera.collectAsStateWithLifecycle()
    val trafficFlowTileUrls by vm.trafficFlowTileUrls.collectAsStateWithLifecycle()

    var recenterTrigger by remember { mutableIntStateOf(0) }
    val mapCenter = remember(recenterTrigger) {
        userLocation?.latLng ?: LatLng(48.1351, 11.5820)
    }

    Box(Modifier.fillMaxSize()) {
        NavMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = MapStyleProvider.styleUrl(connectivity, isNavigating = false),
            cameraPosition = mapCenter,
            zoom = 14.0,
            recenterTrigger = recenterTrigger,
            cameras = cameraMarkers,
            trafficFlowTileUrls = trafficFlowTileUrls,
            onCameraIdle = vm::onViewportChanged,
            onCameraTap = vm::onCameraTapped,
            vehicleType = settings.vehicleType,
            userPosition = userLocation?.latLng,
            userBearing = userLocation?.bearingDeg ?: 0f,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SearchBar(
                onClick = onSearchTap,
                onSettingsClick = onSettingsTap,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val homePlace = settings.homePlace
                val workPlace = settings.workPlace
                ShortcutPill(
                    emoji = "🏠",
                    label = homePlace?.label?.take(18) ?: "Home",
                    isSet = homePlace != null,
                    onClick = {
                        if (homePlace != null) onNavigateTo(LatLng(homePlace.lat, homePlace.lng), homePlace.label)
                        else onSetHome()
                    },
                    modifier = Modifier.weight(1f),
                )
                ShortcutPill(
                    emoji = "💼",
                    label = workPlace?.label?.take(18) ?: "Work",
                    isSet = workPlace != null,
                    onClick = {
                        if (workPlace != null) onNavigateTo(LatLng(workPlace.lat, workPlace.lng), workPlace.label)
                        else onSetWork()
                    },
                    modifier = Modifier.weight(1f),
                )
                ShortcutPill(
                    emoji = "92",
                    label = "Trips",
                    isSet = true,
                    onClick = onTripsTap,
                    modifier = Modifier.weight(1f),
                )
            }

            if (connectivity != ConnectivityState.FULL) {
                ConnectivityBanner(connectivity)
            }
        }

        FreeDriveHud(
            speedKph = userLocation?.speedKph,
            nearbyCamera = nearbyCamera,
            settings = settings,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 24.dp),
        )

        // My Location button — re-centers map on current GPS position
        MyLocationFab(
            onClick = { recenterTrigger++ },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 24.dp),
        )

        selectedCamera?.let { camera ->
            CameraDetailsSheet(
                camera = camera,
                units = settings.units,
                onDismiss = vm::dismissCameraDetails,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
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
    val showCamera = settings.showSpeedCameras && nearbyCamera != null && speedKph != null && speedKph > 10f

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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            UnknownLimitBadge()
        }
    }
}

@Composable
private fun UnknownLimitBadge() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color.White, CircleShape)
            .border(3.dp, Color(0xFFCC0000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("--", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    text = cameraLabel(camera),
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

private fun cameraLabel(camera: NearbyCamera) = when {
    camera.type == CameraType.FIXED_SPEED && camera.speedLimitKph == null -> "Camera ahead"
    camera.type == CameraType.MOBILE_ZONE && camera.speedLimitKph == null -> "Camera zone ahead"
    else -> cameraLabel(camera.type)
}

@Composable
private fun CameraDetailsSheet(
    camera: CameraMarker,
    units: DistanceUnits,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = camera.typeTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = camera.limitText(units),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(10.dp))
            DetailRow(label = "Source", value = camera.source.orEmpty().ifBlank { "Unknown" })
            DetailRow(label = "Confidence", value = camera.confidenceText())
            DetailRow(label = "Last updated", value = camera.lastUpdatedText())
            DetailRow(label = "Limit", value = if (camera.speedLimitKph == null) "Unknown camera limit" else "Known camera limit")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

private fun CameraMarker.typeTitle(): String = when (typeCode) {
    CameraType.FIXED_SPEED.name -> "Speed camera"
    CameraType.RED_LIGHT.name -> "Red light camera"
    CameraType.COMBINED.name -> "Combined camera"
    CameraType.AVERAGE_SPEED_START.name -> "Average speed start"
    CameraType.AVERAGE_SPEED_END.name -> "Average speed end"
    CameraType.MOBILE_ZONE.name -> "Mobile camera zone"
    CameraType.SECTION_CONTROL.name -> "Section control"
    else -> "Camera"
}

private fun CameraMarker.limitText(units: DistanceUnits): String {
    val limit = speedLimitKph ?: return "Camera limit unknown"
    return if (units == DistanceUnits.METRIC) {
        "$limit km/h"
    } else {
        "${(limit * 0.621371).roundToInt()} mph"
    }
}

private fun CameraMarker.confidenceText(): String {
    val value = confidence ?: return "Unknown"
    return "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"
}

private fun CameraMarker.lastUpdatedText(): String {
    val timestamp = lastUpdatedMs ?: return "Unknown"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}

@Composable
private fun SearchBar(
    onClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Where to?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp),
            ) {
                IconButton(onClick = onSettingsClick, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutPill(
    emoji: String,
    label: String,
    onClick: () -> Unit,
    isSet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!isSet) {
                Spacer(Modifier.width(4.dp))
                Text("+", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyLocationFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Rounded.MyLocation,
                contentDescription = "My location",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
