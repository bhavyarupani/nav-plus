@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.navplus.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.navplus.core.settings.*

// ── Internal page model ───────────────────────────────────────────────────────

private sealed class SettingsPage(val title: String) {
    object Main : SettingsPage("Settings")
    object Navigation : SettingsPage("Navigation")
    object MapAppearance : SettingsPage("Map & Appearance")
    object SafetyCameras : SettingsPage("Safety & Cameras")
    object TrafficRoadAhead : SettingsPage("Traffic & Road Ahead")
    object TrafficSignals : SettingsPage("Traffic Signals")
    object RealWorldFeel : SettingsPage("Real-World Feel")
    object LaneGuidanceSigns : SettingsPage("Lane Guidance & Signs")
    object SmartStops : SettingsPage("Smart Stops")
    object Fuel : SettingsPage("Fuel")
    object GroupDrive : SettingsPage("Group Drive")
    object VoiceAlerts : SettingsPage("Voice & Alerts")
    object Privacy : SettingsPage("Privacy & Data")
    object Accessibility : SettingsPage("Accessibility")
    object About : SettingsPage("About")
}

// ── Root composable ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSetHome: () -> Unit = {},
    onSetWork: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pageStack = remember { mutableStateListOf<SettingsPage>() }
    val currentPage = pageStack.lastOrNull() ?: SettingsPage.Main

    BackHandler(enabled = pageStack.isNotEmpty()) {
        pageStack.removeLastOrNull()
    }

    val navigateTo: (SettingsPage) -> Unit = { page -> pageStack.add(page) }
    val navigateBack: () -> Unit = {
        if (pageStack.isNotEmpty()) pageStack.removeLastOrNull()
        else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentPage.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (currentPage) {
                is SettingsPage.Main -> MainSettingsPage(settings, navigateTo, vm, onSetHome, onSetWork)
                is SettingsPage.Navigation -> NavigationSettingsPage(settings, vm)
                is SettingsPage.MapAppearance -> MapAppearancePage(settings, vm)
                is SettingsPage.SafetyCameras -> SafetyCamerasPage(settings, vm)
                is SettingsPage.TrafficRoadAhead -> TrafficRoadAheadPage(settings, vm)
                is SettingsPage.TrafficSignals -> TrafficSignalsPage(settings, vm)
                is SettingsPage.RealWorldFeel -> RealWorldFeelPage(settings, vm)
                is SettingsPage.LaneGuidanceSigns -> LaneGuidanceSignsPage(settings, vm)
                is SettingsPage.SmartStops -> SmartStopsPage(settings, vm)
                is SettingsPage.Fuel -> FuelPage(settings, vm)
                is SettingsPage.GroupDrive -> GroupDrivePage(settings, vm)
                is SettingsPage.VoiceAlerts -> VoiceAlertsPage(settings, vm)
                is SettingsPage.Privacy -> PrivacyPage(settings, vm)
                is SettingsPage.Accessibility -> AccessibilityPage(settings, vm)
                is SettingsPage.About -> AboutPage()
            }
        }
    }
}

// ── Main settings page ────────────────────────────────────────────────────────

@Composable
private fun MainSettingsPage(
    settings: UserSettings,
    navigate: (SettingsPage) -> Unit,
    vm: SettingsViewModel,
    onSetHome: () -> Unit = {},
    onSetWork: () -> Unit = {},
) {
    data class CategoryItem(
        val icon: String,
        val title: String,
        val subtitle: String,
        val page: SettingsPage,
    )

    val categories = listOf(
        CategoryItem("🗺", "Navigation", "Route type, rerouting, alternatives", SettingsPage.Navigation),
        CategoryItem("🎨", "Map & Appearance", "Theme, perspective, layers, zoom", SettingsPage.MapAppearance),
        CategoryItem("📷", "Safety & Cameras", "Cameras, speed alerts, school zones", SettingsPage.SafetyCameras),
        CategoryItem("🚗", "Traffic & Road Ahead", "Traffic layer, alerts, road ahead panel", SettingsPage.TrafficRoadAhead),
        CategoryItem("🚦", "Traffic Signals", "Signal intelligence, GLOSA, timing", SettingsPage.TrafficSignals),
        CategoryItem("◐", "Real-World Feel", "Sky, aircraft, rail, landmarks and road mood", SettingsPage.RealWorldFeel),
        CategoryItem("🛤", "Lane Guidance & Signs", "Lanes, signboards, exit numbers", SettingsPage.LaneGuidanceSigns),
        CategoryItem("⛽", "Smart Stops", "Fuel and shop quick actions", SettingsPage.SmartStops),
        CategoryItem("🛢", "Fuel", "Fuel type, preferences, detour limit", SettingsPage.Fuel),
        CategoryItem("👥", "Group Drive", "Convoy, sharing, notifications", SettingsPage.GroupDrive),
        CategoryItem("🔊", "Voice & Alerts", "Guidance mode, timing, sounds", SettingsPage.VoiceAlerts),
        CategoryItem("🔒", "Privacy & Data", "History, analytics, data controls", SettingsPage.Privacy),
        CategoryItem("♿", "Accessibility", "Large buttons, contrast, motion", SettingsPage.Accessibility),
        CategoryItem("ℹ", "About", "App version and information", SettingsPage.About),
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            VehicleSection(settings, vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Saved Places")
            SavedPlaceRow(
                emoji = "🏠", label = "Home",
                savedLabel = settings.homePlace?.label,
                onTap = onSetHome,
            )
            SavedPlaceRow(
                emoji = "💼", label = "Work",
                savedLabel = settings.workPlace?.label,
                onTap = onSetWork,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Features")
        }
        items(categories) { cat ->
            CategoryRow(
                icon = cat.icon,
                title = cat.title,
                subtitle = cat.subtitle,
                onClick = { navigate(cat.page) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Units")
            RadioGroupRow(
                label = "Distance",
                options = listOf("Metric (km)" to DistanceUnits.METRIC, "Imperial (miles)" to DistanceUnits.IMPERIAL),
                selected = settings.units,
                onSelect = vm::setUnits,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VehicleSection(settings: UserSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmaps = remember(density) {
        VehicleType.entries.associateWith { VehicleIconFactory.create(context, it, density) }
    }

    Column(Modifier.padding(16.dp)) {
        SectionLabel("Your Vehicle")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VehicleType.entries.forEach { type ->
                val isSelected = type == settings.vehicleType
                val bitmap = bitmaps.getValue(type)
                OutlinedCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp)
                        .clickable { vm.setVehicleType(type) },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        Modifier
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
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            type.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// ── Saved place row ───────────────────────────────────────────────────────────

@Composable
private fun SavedPlaceRow(emoji: String, label: String, savedLabel: String?, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                savedLabel ?: "Not set — tap to add",
                style = MaterialTheme.typography.bodySmall,
                color = if (savedLabel != null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Navigation settings ───────────────────────────────────────────────────────

@Composable
private fun NavigationSettingsPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Route Type") }
        item {
            RadioGroupColumn(
                options = RouteType.entries.map { it.label to it },
                selected = settings.routeType,
                onSelect = vm::setRouteType,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Avoid") }
        item { ToggleRow("Toll roads", "Route around motorways with tolls", settings.avoidTolls, vm::setAvoidTolls) }
        item { ToggleRow("Motorways", "Stay on local and regional roads", settings.avoidHighways, vm::setAvoidHighways) }
        item { ToggleRow("Ferries", "Route over land where possible", settings.avoidFerries, vm::setAvoidFerries) }
        item { ToggleRow("Unpaved roads", "Prefer surfaced roads", settings.avoidUnpavedRoads, vm::setAvoidUnpavedRoads) }
        item { ToggleRow("Narrow roads", "Avoid single-track lanes", settings.avoidNarrowRoads, vm::setAvoidNarrowRoads) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Route Recalculation") }
        item { ToggleRow("Automatic rerouting", "Recalculate when you leave the route", settings.autoReroute, vm::setAutoReroute) }
        item { ToggleRow("Ask before major changes", "Confirm reroutes that add more than 2 min", settings.askBeforeMajorReroute, vm::setAskBeforeMajorReroute) }
        item {
            SectionLabel("Accept faster route automatically")
            RadioGroupColumn(
                options = FasterRouteThreshold.entries.map { it.label to it },
                selected = settings.autoAcceptFasterRoute,
                onSelect = vm::setAutoAcceptFasterRoute,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Route Alternatives") }
        item {
            RadioGroupColumn(
                options = AlternativesMode.entries.map { it.label to it },
                selected = settings.alternativesMode,
                onSelect = vm::setAlternativesMode,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Map & Appearance ──────────────────────────────────────────────────────────

@Composable
private fun MapAppearancePage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Theme") }
        item {
            RadioGroupColumn(
                options = MapTheme.entries.map { it.label to it },
                selected = settings.mapTheme,
                onSelect = vm::setMapTheme,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Map Perspective") }
        item {
            RadioGroupColumn(
                options = MapPerspective.entries.map { it.label to it },
                selected = settings.mapPerspective,
                onSelect = vm::setMapPerspective,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Heading Mode") }
        item {
            RadioGroupColumn(
                options = HeadingMode.entries.map { it.label to it },
                selected = settings.headingMode,
                onSelect = vm::setHeadingMode,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Map Layers") }
        item { ToggleRow("3D buildings", "Show extruded buildings in urban areas", settings.show3dBuildings, vm::setShow3dBuildings) }
        item { ToggleRow("Terrain", "Show elevation shading on the map", settings.showTerrain, vm::setShowTerrain) }
        item { ToggleRow("Hill shading", "Subtle relief shading", settings.showHillShading, vm::setShowHillShading) }
        item { ToggleRow("Traffic", "Colour-coded traffic conditions", settings.showTrafficLayer, vm::setShowTrafficLayer) }
        item { ToggleRow("Points of interest", "Show shops, restaurants and services", settings.showPoiLayer, vm::setShowPoiLayer) }
        item { ToggleRow("Group vehicles", "Show convoy cars on the map", settings.showGroupCarsOnMap, vm::setShowGroupCarsOnMap) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Map Detail Level") }
        item {
            RadioGroupColumn(
                options = MapDetailLevel.entries.map { it.label to it },
                selected = settings.mapDetailLevel,
                onSelect = vm::setMapDetailLevel,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Navigation") }
        item { ToggleRow("3D map tilt", "Perspective view while navigating", settings.navMapTilt, vm::setNavMapTilt) }
        item { ToggleRow("Keep screen on", "Prevent sleep during navigation", settings.keepScreenOn, vm::setKeepScreenOn) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Auto-Zoom") }
        item { ToggleRow("Automatic zoom", "Adjust zoom to speed and manoeuvres", settings.autoZoom, vm::setAutoZoom) }
        item { ToggleRow("Zoom by speed", "Zoom out at high speed", settings.autoZoomBySpeed, vm::setAutoZoomBySpeed) }
        item { ToggleRow("Zoom on intersections", "Zoom in at complex intersections", settings.autoZoomOnIntersections, vm::setAutoZoomOnIntersections) }
        item { ToggleRow("Zoom on roundabouts", "Zoom in at roundabouts", settings.autoZoomOnRoundabouts, vm::setAutoZoomOnRoundabouts) }
        item { ToggleRow("Zoom on motorway exits", "Zoom in before exits", settings.autoZoomOnMotorwayExits, vm::setAutoZoomOnMotorwayExits) }
        item { ToggleRow("Zoom with lane guidance", "Zoom in when lanes are shown", settings.autoZoomOnLaneGuidance, vm::setAutoZoomOnLaneGuidance) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Safety & Cameras ──────────────────────────────────────────────────────────

@Composable
private fun SafetyCamerasPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Safety features",
                description = "Enable all safety and camera warnings",
                checked = settings.safetyFeaturesEnabled,
                onCheckedChange = vm::setSafetyFeaturesEnabled,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Camera Types") }
        item { ToggleRow("Speed cameras", "Fixed speed enforcement", settings.showSpeedCameras, vm::setShowSpeedCameras) }
        item { ToggleRow("Red-light cameras", "Traffic-light enforcement", settings.showRedLightCameras, vm::setShowRedLightCameras) }
        item { ToggleRow("Combined cameras", "Speed and red-light combined", settings.showCombinedCameras, vm::setShowCombinedCameras) }
        item { ToggleRow("Average-speed zones", "Section control enforcement", settings.showAverageSpeedZones, vm::setShowAverageSpeedZones) }
        item { ToggleRow("Mobile enforcement zones", "Areas with mobile speed cameras", settings.showMobileEnforcement, vm::setShowMobileEnforcement) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Alert Distance") }
        item {
            RadioGroupColumn(
                options = CameraAlertDistance.entries.map { it.label to it },
                selected = settings.cameraAlertDistance,
                onSelect = vm::setCameraAlertDistance,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Alert Style") }
        item {
            RadioGroupColumn(
                options = CameraAlertStyle.entries.map { it.label to it },
                selected = settings.cameraAlertStyle,
                onSelect = vm::setCameraAlertStyle,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Camera Display") }
        item { ToggleRow("Show cameras on map", "Display camera icons", settings.showCameraOnMap, vm::setShowCameraOnMap) }
        item { ToggleRow("Show distance", "Distance to next camera", settings.showCameraDistance, vm::setShowCameraDistance) }
        item { ToggleRow("Show known speed limit", "Display the camera's enforcement limit", settings.showCameraSpeedLimit, vm::setShowCameraSpeedLimit) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Speed Display") }
        item { ToggleRow("Current speed", "Show your speed while driving", settings.showCurrentSpeed, vm::setShowCurrentSpeed) }
        item { ToggleRow("Speed limit", "Show the current road's speed limit", settings.showSpeedLimit, vm::setShowSpeedLimit) }
        item { SectionLabel("Speed Warning Threshold") }
        item {
            RadioGroupColumn(
                options = SpeedWarningThreshold.entries.map { it.label to it },
                selected = settings.speedWarningThreshold,
                onSelect = vm::setSpeedWarningThreshold,
            )
        }
        item { ToggleRow("Visual warning", "Red speed display when over limit", settings.speedWarningVisual, vm::setSpeedWarningVisual) }
        item { ToggleRow("Audio warning", "Sound alert when over limit", settings.speedWarningAudio, vm::setSpeedWarningAudio) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Hazards") }
        item { ToggleRow("School zones", "Warn near school zones", settings.showSchoolZones, vm::setShowSchoolZones) }
        item { ToggleRow("Roadworks", "Roadworks and temporary closures", settings.showRoadworksAlerts, vm::setShowRoadworksAlerts) }
        item { ToggleRow("Accidents", "Community-reported accidents", settings.showAccidentAlerts, vm::setShowAccidentAlerts) }
        item { ToggleRow("Weather warnings", "Ice, fog, rain and storm alerts", settings.showWeatherWarnings, vm::setShowWeatherWarnings) }
        item { ToggleRow("Sharp curves", "Warn before tight bends", settings.showSharpCurveWarnings, vm::setShowSharpCurveWarnings) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Traffic & Road Ahead ──────────────────────────────────────────────────────

@Composable
private fun TrafficRoadAheadPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Traffic information",
                description = "Live traffic and incident data",
                checked = settings.trafficFeaturesEnabled,
                onCheckedChange = vm::setTrafficFeaturesEnabled,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Traffic") }
        item { ToggleRow("Traffic-aware ETA", "Adjust arrival time for current traffic", settings.trafficAwareEta, vm::setTrafficAwareEta) }
        item { ToggleRow("Traffic-aware rerouting", "Automatically avoid congestion", settings.trafficAwareRerouting, vm::setTrafficAwareRerouting) }
        item { ToggleRow("Traffic alerts", "Notify about incidents on your route", settings.trafficAlerts, vm::setTrafficAlerts) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            MasterToggleRow(
                label = "Road Ahead panel",
                description = "What's coming up on your route",
                checked = settings.showRoadAhead,
                onCheckedChange = vm::setShowRoadAhead,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Road Ahead Categories") }
        item { ToggleRow("Cameras", "Show cameras ahead", settings.roadAheadShowCameras, vm::setRoadAheadShowCameras) }
        item { ToggleRow("Traffic", "Show congestion ahead", settings.roadAheadShowTraffic, vm::setRoadAheadShowTraffic) }
        item { ToggleRow("Accidents", "Show incidents ahead", settings.roadAheadShowAccidents, vm::setRoadAheadShowAccidents) }
        item { ToggleRow("Roadworks", "Show roadworks ahead", settings.roadAheadShowRoadworks, vm::setRoadAheadShowRoadworks) }
        item { ToggleRow("Weather", "Show weather changes ahead", settings.roadAheadShowWeather, vm::setRoadAheadShowWeather) }
        item { ToggleRow("Fuel stations", "Show fuel opportunities ahead", settings.roadAheadShowFuel, vm::setRoadAheadShowFuel) }
        item { ToggleRow("Rest areas", "Show services and rest areas", settings.roadAheadShowRestAreas, vm::setRoadAheadShowRestAreas) }
        item { ToggleRow("Borders", "Show upcoming border crossings", settings.roadAheadShowBorders, vm::setRoadAheadShowBorders) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Road Ahead Distance") }
        item {
            RadioGroupColumn(
                options = RoadAheadDistance.entries.map { it.label to it },
                selected = settings.roadAheadDistance,
                onSelect = vm::setRoadAheadDistance,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Traffic Signals ───────────────────────────────────────────────────────────

@Composable
private fun TrafficSignalsPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Traffic signal intelligence",
                description = "Use signal phase and timing data where available",
                checked = settings.trafficSignalIntelligence,
                onCheckedChange = vm::setTrafficSignalIntelligence,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Signal Display") }
        item { ToggleRow("Static traffic lights", "Show signal positions on map", settings.showStaticTrafficLights, vm::setShowStaticTrafficLights) }
        item { ToggleRow("Signal timing", "Show countdown when available", settings.showSignalTiming, vm::setShowSignalTiming) }
        item { ToggleRow("Distance to signal", "Show how far to next signal", settings.showSignalDistance, vm::setShowSignalDistance) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            ToggleRow(
                label = "GLOSA — Advisory speed",
                description = "Shows a recommended speed to arrive at green. Only shown when reliable data is available.",
                checked = settings.showGlosa,
                onCheckedChange = vm::setShowGlosa,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Real-World Feel ──────────────────────────────────────────────────────────

@Composable
private fun RealWorldFeelPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Real-world feel",
                description = "Ambient live-world cues while keeping navigation clear",
                checked = settings.realWorldFeelEnabled,
                onCheckedChange = vm::setRealWorldFeelEnabled,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Sky & motion") }
        item { ToggleRow("Visible aircraft", "Show aircraft only when plausible through windshield or side windows", settings.showVisibleAircraft, vm::setShowVisibleAircraft) }
        item { ToggleRow("Airport approach", "Show subtle runway approach/departure context near airports", settings.showAirportApproach, vm::setShowAirportApproach) }
        item { ToggleRow("Sky and light", "Match the map mood to rain, fog, snow, sunset and night", settings.showSkyAndLightReality, vm::setShowSkyAndLightReality) }
        item { ToggleRow("Sun glare", "Warn when low sun is in the windshield or side-window direction", settings.showSunGlareWarning, vm::setShowSunGlareWarning) }
        item { ToggleRow("Real weather ahead", "Render rain, snow or fog only in the affected route corridor", settings.showRealWeatherAhead, vm::setShowRealWeatherAhead) }
        item { ToggleRow("Fog depth layer", "Fade the route slightly when low visibility is ahead", settings.showFogDepthLayer, vm::setShowFogDepthLayer) }
        item { ToggleRow("Storm cell encounter", "Show where forecast rain crosses your ETA on the route", settings.showStormCellEncounter, vm::setShowStormCellEncounter) }
        item { ToggleRow("Moon and night sky", "Subtle clear-night ambience on rural roads", settings.showMoonNightSky, vm::setShowMoonNightSky) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Places ahead") }
        item { ToggleRow("Rail crossing intelligence", "Check route ETA against nearby train movement when available", settings.showRailCrossingIntelligence, vm::setShowRailCrossingIntelligence) }
        item { ToggleRow("Roadside landmarks", "Show visible landmarks only in calm driving moments", settings.showRoadsideLandmarks, vm::setShowRoadsideLandmarks) }
        item { ToggleRow("Water, ferry and bridge moments", "Show rivers, bridge context and ferry timing when relevant", settings.showWaterFerryBridgeMoments, vm::setShowWaterFerryBridgeMoments) }
        item { ToggleRow("Event crowd pulse", "Show crowd/exit traffic zones near venues, stations and airports", settings.showEventCrowdPulse, vm::setShowEventCrowdPulse) }
        item { ToggleRow("Emergency awareness", "Show official emergency closures or response zones only on your route", settings.showEmergencyVehicleAwareness, vm::setShowEmergencyVehicleAwareness) }
        item { ToggleRow("Visible hazard scene", "Minimal icon for roadworks, accident or closure scenes ahead", settings.showVisibleHazardScene, vm::setShowVisibleHazardScene) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Road feel") }
        item { ToggleRow("Ambient route pulse", "Route color breathes for calm, traffic, weather, GPS or emergency state", settings.showAmbientRoutePulse, vm::setShowAmbientRoutePulse) }
        item { ToggleRow("Wind flow", "Show subtle crosswind streaks on exposed roads, bridges and mountains", settings.showWindFlow, vm::setShowWindFlow) }
        item { ToggleRow("Road surface feel", "Reflect wet, icy, gravel or rough surfaces when relevant", settings.showRoadSurfaceFeel, vm::setShowRoadSurfaceFeel) }
        item { ToggleRow("Destination arrival mood", "Prepare parking, walking handoff and destination weather near arrival", settings.showDestinationArrivalMood, vm::setShowDestinationArrivalMood) }
        item { ToggleRow("Wildlife risk", "Subtle rural dusk/night caution when risk is meaningful", settings.showWildlifeRiskAtmosphere, vm::setShowWildlifeRiskAtmosphere) }
        item { ToggleRow("Road feel mode", "Reflect tunnel, bridge, forest, mountain, city and open-road context", settings.showRoadFeelMode, vm::setShowRoadFeelMode) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Lane Guidance & Signs ─────────────────────────────────────────────────────

@Composable
private fun LaneGuidanceSignsPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Lane guidance",
                description = "Show which lane to be in before turns",
                checked = settings.showLaneGuidance,
                onCheckedChange = vm::setShowLaneGuidance,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Lane Options") }
        item { ToggleRow("Early preview", "Show lanes further in advance", settings.laneGuidanceEarlyPreview, vm::setLaneGuidanceEarlyPreview) }
        item { ToggleRow("Highlight recommended lanes", "Highlight your lane in blue", settings.showHighlightedRecommendedLanes, vm::setShowHighlightedRecommendedLanes) }
        item { ToggleRow("Lane endings", "Warn when your lane ends", settings.showLaneEndings, vm::setShowLaneEndings) }
        item { ToggleRow("Lane additions", "Show when a new lane appears", settings.showLaneAdditions, vm::setShowLaneAdditions) }
        item { ToggleRow("Exit-only lanes", "Flag lanes that leave your route", settings.showExitOnlyLanes, vm::setShowExitOnlyLanes) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            MasterToggleRow(
                label = "Navigation signboards",
                description = "Motorway-style exit and junction signs",
                checked = settings.showSignboards,
                onCheckedChange = vm::setShowSignboards,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Signboard Content") }
        item { ToggleRow("Exit numbers", "Show exit and junction numbers", settings.showExitNumbers, vm::setShowExitNumbers) }
        item { ToggleRow("Road numbers", "Show motorway and road shields", settings.showRoadNumbers, vm::setShowRoadNumbers) }
        item { ToggleRow("Destination names", "Show place names on signs", settings.showDestinationNames, vm::setShowDestinationNames) }
        item { ToggleRow("Road personality", "Show motorway, tunnel and scenic chips", settings.showRoadPersonality, vm::setShowRoadPersonality) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Smart Stops ───────────────────────────────────────────────────────────────

@Composable
private fun SmartStopsPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Smart Stops",
                description = "Intelligent fuel, shop and rest suggestions",
                checked = settings.smartStopsEnabled,
                onCheckedChange = vm::setSmartStopsEnabled,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Navigation Quick Actions") }
        item { ToggleRow("Fuel button", "Show ⛽ Fuel during navigation", settings.showFuelButton, vm::setShowFuelButton) }
        item { ToggleRow("Shop button", "Show 🛒 Shop during navigation", settings.showShopButton, vm::setShowShopButton) }
        item { ToggleRow("Toilet button", "Show 🚻 Toilet during navigation", settings.showToiletButton, vm::setShowToiletButton) }
        item { ToggleRow("Coffee button", "Show ☕ Coffee during navigation", settings.showCoffeeButton, vm::setShowCoffeeButton) }
        item { ToggleRow("EV charging button", "Show ⚡ Charge during navigation", settings.showEvButton, vm::setShowEvButton) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Stop Selection") }
        item { ToggleRow("Instant best result", "Recommend one best option immediately", settings.smartStopInstantResult, vm::setSmartStopInstantResult) }
        item { ToggleRow("Only open places", "Exclude places that may be closed", settings.smartStopOnlyOpen, vm::setSmartStopOnlyOpen) }
        item { ToggleRow("Require parking", "Only suggest stops with parking", settings.smartStopRequireParking, vm::setSmartStopRequireParking) }
        item { ToggleRow("Avoid U-turns", "Prefer stops that don't require a U-turn", settings.smartStopAvoidUTurn, vm::setSmartStopAvoidUTurn) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            SectionLabel("Maximum detour")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 15, 20).forEach { min ->
                    FilterChip(
                        selected = settings.smartStopMaxDetourMinutes == min,
                        onClick = { vm.setSmartStopMaxDetourMinutes(min) },
                        label = { Text("${min} min") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Fuel ──────────────────────────────────────────────────────────────────────

@Composable
private fun FuelPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Fuel Type") }
        item {
            RadioGroupColumn(
                options = FuelType.entries.map { it.label to it },
                selected = settings.fuelType,
                onSelect = vm::setFuelType,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Optimisation Preference") }
        item {
            RadioGroupColumn(
                options = FuelPreference.entries.map { it.label to it },
                selected = settings.fuelPreference,
                onSelect = vm::setFuelPreference,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Maximum Detour") }
        item {
            RadioGroupColumn(
                options = FuelDetourLimit.entries.map { it.label to it },
                selected = settings.fuelDetourLimit,
                onSelect = vm::setFuelDetourLimit,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Supermarket Preference") }
        item {
            RadioGroupColumn(
                options = SupermarketPreference.entries.map { it.label to it },
                selected = settings.supermarketPreference,
                onSelect = vm::setSupermarketPreference,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Group Drive ───────────────────────────────────────────────────────────────

@Composable
private fun GroupDrivePage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MasterToggleRow(
                label = "Group Drive",
                description = "Convoy navigation with other vehicles",
                checked = settings.groupDriveEnabled,
                onCheckedChange = vm::setGroupDriveEnabled,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Display") }
        item { ToggleRow("Group panel", "Show group members in navigation", settings.showGroupPanel, vm::setShowGroupPanel) }
        item { ToggleRow("Vehicles on map", "Show convoy cars on the map", settings.showGroupCarsOnMap, vm::setShowGroupCarsOnMap) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Sharing") }
        item { ToggleRow("Share my location", "Other members see where you are", settings.shareLocationWithGroup, vm::setShareLocationWithGroup) }
        item { ToggleRow("Share my ETA", "Other members see your arrival time", settings.shareEtaWithGroup, vm::setShareEtaWithGroup) }
        item { ToggleRow("Share my speed", "Other members see your current speed", settings.shareSpeedWithGroup, vm::setShareSpeedWithGroup) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Gap Threshold") }
        item {
            RadioGroupColumn(
                options = GroupGapThreshold.entries.map { it.label to it },
                selected = settings.groupGapThreshold,
                onSelect = vm::setGroupGapThreshold,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Notifications") }
        item { ToggleRow("Vehicle falling behind", "Alert when a member is more than the threshold behind", settings.groupNotifyVehicleBehind, vm::setGroupNotifyVehicleBehind) }
        item { ToggleRow("Vehicle stopped", "Alert when a member has stopped", settings.groupNotifyVehicleStopped, vm::setGroupNotifyVehicleStopped) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Leader Stop Acceptance") }
        item { ToggleRow("Auto-accept fuel stops", "Join fuel stops the leader adds", settings.groupAutoAcceptLeaderFuel, vm::setGroupAutoAcceptLeaderFuel) }
        item { ToggleRow("Auto-accept shop stops", "Join shop stops the leader adds", settings.groupAutoAcceptLeaderShop, vm::setGroupAutoAcceptLeaderShop) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Voice & Alerts ────────────────────────────────────────────────────────────

@Composable
private fun VoiceAlertsPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Guidance Mode") }
        item {
            RadioGroupColumn(
                options = VoiceGuidanceMode.entries.map { it.label to it },
                selected = settings.voiceGuidanceMode,
                onSelect = vm::setVoiceGuidanceMode,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Announce") }
        item { ToggleRow("Street names", "Read out street names when turning", settings.voiceIncludesStreetNames, vm::setVoiceIncludesStreetNames) }
        item { ToggleRow("Road numbers", "Read out motorway and road numbers", settings.voiceIncludesRoadNumbers, vm::setVoiceIncludesRoadNumbers) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Announcement Timing") }
        item {
            RadioGroupColumn(
                options = VoiceTiming.entries.map { it.label to it },
                selected = settings.voiceTiming,
                onSelect = vm::setVoiceTiming,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Privacy & Data ────────────────────────────────────────────────────────────

@Composable
private fun PrivacyPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Privacy Preset") }
        item {
            RadioGroupColumn(
                options = PrivacyPreset.entries.map { it.label to it },
                selected = settings.privacyPreset,
                onSelect = vm::setPrivacyPreset,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("History") }
        item { ToggleRow("Recent search history", "Remember recent searches", settings.recentSearchHistoryEnabled, vm::setRecentSearchHistoryEnabled) }
        item { ToggleRow("Recent destinations", "Remember where you've navigated to", settings.recentDestinationHistoryEnabled, vm::setRecentDestinationHistoryEnabled) }
        item { ToggleRow("Trip history", "Store completed trip records", settings.tripHistoryEnabled, vm::setTripHistoryEnabled) }
        item {
            if (settings.tripHistoryEnabled) {
                Column {
                    SectionLabel("History Retention")
                    RadioGroupColumn(
                        options = TripHistoryRetention.entries.map { it.label to it },
                        selected = settings.tripHistoryRetention,
                        onSelect = vm::setTripHistoryRetention,
                    )
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionLabel("Diagnostics") }
        item { ToggleRow("Analytics", "Anonymous usage statistics", settings.analyticsEnabled, vm::setAnalyticsEnabled) }
        item { ToggleRow("Crash reports", "Send crash reports to help improve the app", settings.crashReportsEnabled, vm::setCrashReportsEnabled) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionLabel("Delete Data")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your data is stored locally on this device. Clearing history permanently removes those records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear all local data")
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Accessibility ─────────────────────────────────────────────────────────────

@Composable
private fun AccessibilityPage(settings: UserSettings, vm: SettingsViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Display") }
        item { ToggleRow("Large buttons", "Increase tap target size during navigation", settings.largeButtonsMode, vm::setLargeButtonsMode) }
        item { ToggleRow("High contrast", "Increase contrast for better readability", settings.highContrastMode, vm::setHighContrastMode) }
        item { ToggleRow("Reduce motion", "Minimise animation and transitions", settings.reduceMotion, vm::setReduceMotion) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── About ─────────────────────────────────────────────────────────────────────

@Composable
private fun AboutPage() {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nav Plus", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Premium European navigation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Text("Version 1.0.0-alpha", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text("Offline maps") },
                supportingContent = { Text("OpenStreetMap contributors") },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Routing") },
                supportingContent = { Text("OSRM / GraphHopper / TomTom") },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Search") },
                supportingContent = { Text("Photon / TomTom Search") },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Camera data") },
                supportingContent = { Text("OpenStreetMap Overpass API") },
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Reusable primitives ───────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun CategoryRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 20.sp)
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun MasterToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
        )
    }
}

@Composable
private fun <T> RadioGroupColumn(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        options.forEach { (label, value) ->
            ListItem(
                modifier = Modifier.clickable { onSelect(value) },
                leadingContent = {
                    RadioButton(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                    )
                },
                headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            )
        }
    }
}

@Composable
private fun <T> RadioGroupRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEach { (optLabel, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Spacer(Modifier.width(8.dp))
                Text(optLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
