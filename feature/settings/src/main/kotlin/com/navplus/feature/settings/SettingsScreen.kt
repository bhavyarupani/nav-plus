package com.navplus.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.map.VehicleIconFactory
import com.navplus.core.settings.DistanceUnits
import com.navplus.core.settings.UserSettings
import com.navplus.core.settings.VehicleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item { SectionHeader("Navigation Alerts") }
            item {
                ToggleRow(
                    label = "Speed cameras",
                    description = "Show camera alerts and speed limits ahead",
                    emoji = "📷",
                    checked = settings.showSpeedCameras,
                    onCheckedChange = vm::setShowSpeedCameras,
                )
            }
            item {
                ToggleRow(
                    label = "Speed limit display",
                    description = "Show current speed limit while driving",
                    emoji = "🚦",
                    checked = settings.showSpeedLimit,
                    onCheckedChange = vm::setShowSpeedLimit,
                )
            }
            item {
                ToggleRow(
                    label = "Motorway signboards",
                    description = "Show exit numbers and road shields",
                    emoji = "🛣",
                    checked = settings.showSignboards,
                    onCheckedChange = vm::setShowSignboards,
                )
            }
            item {
                ToggleRow(
                    label = "Lane guidance",
                    description = "Show which lane to be in before turns",
                    emoji = "🛤",
                    checked = settings.showLaneGuidance,
                    onCheckedChange = vm::setShowLaneGuidance,
                )
            }
            item {
                ToggleRow(
                    label = "Border crossing alerts",
                    description = "Speed limits, vignette and fuel reminders",
                    emoji = "🛂",
                    checked = settings.showBorderAlerts,
                    onCheckedChange = vm::setShowBorderAlerts,
                )
            }
            item {
                ToggleRow(
                    label = "Road personality",
                    description = "Show motorway, tunnel and scenic road chips",
                    emoji = "🏔",
                    checked = settings.showRoadPersonality,
                    onCheckedChange = vm::setShowRoadPersonality,
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Route Options") }
            item {
                ToggleRow(
                    label = "Avoid toll roads",
                    description = "Route around motorways with tolls",
                    emoji = "💰",
                    checked = settings.avoidTolls,
                    onCheckedChange = vm::setAvoidTolls,
                )
            }
            item {
                ToggleRow(
                    label = "Avoid motorways",
                    description = "Stay on local and regional roads",
                    emoji = "🛤",
                    checked = settings.avoidHighways,
                    onCheckedChange = vm::setAvoidHighways,
                )
            }
            item {
                ToggleRow(
                    label = "Avoid ferries",
                    description = "Route over land where possible",
                    emoji = "⛴",
                    checked = settings.avoidFerries,
                    onCheckedChange = vm::setAvoidFerries,
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Display") }
            item {
                ToggleRow(
                    label = "3D map tilt",
                    description = "Perspective view while navigating",
                    emoji = "🗺",
                    checked = settings.navMapTilt,
                    onCheckedChange = vm::setNavMapTilt,
                )
            }
            item {
                ToggleRow(
                    label = "Keep screen on",
                    description = "Prevent screen from sleeping during navigation",
                    emoji = "☀",
                    checked = settings.keepScreenOn,
                    onCheckedChange = vm::setKeepScreenOn,
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Distance Units") }
            item {
                RadioRow(
                    label = "Metric",
                    description = "Kilometres and metres",
                    selected = settings.units == DistanceUnits.METRIC,
                    onClick = { vm.setUnits(DistanceUnits.METRIC) },
                )
            }
            item {
                RadioRow(
                    label = "Imperial",
                    description = "Miles and feet",
                    selected = settings.units == DistanceUnits.IMPERIAL,
                    onClick = { vm.setUnits(DistanceUnits.IMPERIAL) },
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Your Vehicle") }
            item {
                VehiclePicker(
                    selected = settings.vehicleType,
                    onSelect = vm::setVehicleType,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun VehiclePicker(
    selected: VehicleType,
    onSelect: (VehicleType) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Same call the map makes, so these previews are the real markers.
    val bitmaps = remember(density) {
        VehicleType.entries.associateWith { VehicleIconFactory.create(context, it, density) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VehicleType.entries.forEach { type ->
            val isSelected = type == selected
            val bitmap = bitmaps.getValue(type)

            OutlinedCard(
                modifier = Modifier
                    .weight(1f)
                    .height(140.dp)
                    .clickable { onSelect(type) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = type.displayName,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = type.displayName,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = type.description,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    emoji: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
