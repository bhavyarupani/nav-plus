package com.roadpulse.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.CameraPerspective
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.PromptVisibilityChangedListener
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SupportNavigationFragment
import com.google.android.libraries.navigation.Waypoint
import com.roadpulse.auto.destination.SelectedDestinationStore
import com.roadpulse.auto.driving.DrivingAttention
import com.roadpulse.auto.driving.RoadAheadEventType
import com.roadpulse.auto.driving.RouteCameraGuidance
import com.roadpulse.auto.driving.RouteCameraSnapshot
import com.roadpulse.auto.driving.RouteMatchConfidence
import com.roadpulse.auto.driving.RouteRoadFeatureGuidance
import com.roadpulse.auto.driving.SpeedLimitAheadGuidance
import com.roadpulse.auto.driving.SpeedLimitAheadSummary
import com.roadpulse.auto.driving.UpcomingRouteCamera
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.map.MapMarkerIconFactory
import com.roadpulse.auto.map.RoadPulseMapTheme
import com.roadpulse.auto.navigation.LaneGuidance
import com.roadpulse.auto.navigation.TurnByTurnState
import com.roadpulse.auto.quota.GoogleUsageGuard
import com.roadpulse.auto.quota.QuotaDecision
import com.roadpulse.auto.settings.DisplayFilterStore
import com.roadpulse.auto.settings.DisplayLayer
import com.roadpulse.auto.settings.DrivingContext
import com.roadpulse.auto.settings.RoadSignFilterStore
import com.roadpulse.auto.settings.RouteStyle
import com.roadpulse.auto.settings.RouteStylePreferencesStore
import com.roadpulse.auto.stops.RouteStopOptimizer
import com.roadpulse.auto.terrain.ElevationProfileSummary
import com.roadpulse.auto.terrain.TerrainGuidance
import java.util.Date
import java.util.Locale

class NavigationActivity : FragmentActivity() {
    private var navigator: Navigator? = null
    private lateinit var status: TextView
    private lateinit var lanePanel: View
    private lateinit var laneStatus: TextView
    private lateinit var laneImage: ImageView
    private lateinit var roadAheadEyebrow: TextView
    private lateinit var roadAheadPanel: View
    private lateinit var roadAheadMeta: TextView
    private lateinit var roadAheadPrimary: TextView
    private lateinit var roadAheadSecondary: TextView
    private lateinit var speedComplianceRing: com.roadpulse.auto.driving.SpeedComplianceRingView
    private var latestTerrain: ElevationProfileSummary? = null
    private var latestSpeedLimit: SpeedLimitAheadSummary? = null
    private var latestRoadFeatures: List<UpcomingRouteRoadFeature> = emptyList()
    private var latestCameraSnapshot = RouteCameraSnapshot()
    private var maneuverDistanceMeters: Int? = null
    private var navigationMap: GoogleMap? = null
    private var navigationFragment: SupportNavigationFragment? = null
    private val routeRoadFeatureMarkers = mutableListOf<Marker>()
    private var routeRoadFeatureMarkerIds: List<String> = emptyList()
    private val routeCameraMarkers = mutableListOf<Marker>()
    private var routeCameraMarkerIds: List<String> = emptyList()
    private val mapMarkerIcons by lazy { MapMarkerIconFactory(this) }
    private val destination by lazy { SelectedDestinationStore(this).load() }
    private var routeRequestIssued = false
    private val routeStopOptimizer by lazy { RouteStopOptimizer(this) }
    private val displayFilters by lazy { DisplayFilterStore(this) }
    private val roadSignFilters by lazy { RoadSignFilterStore(this) }
    private val arrivalListener =
        Navigator.ArrivalListener { event ->
            if (!event.isFinalDestination) {
                navigator?.continueToNextDestination()
                runOnUiThread {
                    status.text = "Stop reached: ${event.waypoint.title}. Continuing to the next destination."
                }
            }
        }
    private val laneListener: (com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo) -> Unit =
        { navInfo ->
            if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.LANE_GUIDANCE)) {
                renderLaneAndSignboardPanel(navInfo)
            } else {
                lanePanel.visibility = View.GONE
            }
            maneuverDistanceMeters = navInfo.distanceToCurrentStepMeters
            renderRoadAhead()
            navigator?.let { refreshRouteIntelligence(it, force = false) }
        }

    /**
     * Prefers RoadPulse's own signboard rendering (Autobahn exit + destinations + lane states)
     * when [com.roadpulse.auto.signage.SignboardGuidanceEngine] has reliable enough data for it;
     * otherwise falls back to Google's own generated lane bitmap and text summary, which is
     * always safe to show verbatim. Never mixes the two in one image.
     */
    private fun renderLaneAndSignboardPanel(navInfo: com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo) {
        val signboardGuidance =
            com.roadpulse.auto.signage.SignboardGuidanceEngine.build(
                navInfo,
                latestRoadFeatures,
                RouteRoadFeatureGuidance.latestLaneTopologySections,
            )
        val signboardBitmap =
            com.roadpulse.auto.signage.SignboardRenderer.render(
                this,
                signboardGuidance,
                signboardWidthPx(),
                SIGNBOARD_HEIGHT_PX,
            )
        if (signboardBitmap != null) {
            val junction = signboardGuidance.junction
            lanePanel.visibility = View.VISIBLE
            laneStatus.text =
                listOfNotNull(
                    junction
                        ?.panels
                        ?.firstOrNull()
                        ?.exitNumber
                        ?.let { "Ausfahrt $it" },
                    junction
                        ?.panels
                        ?.firstOrNull()
                        ?.destinations
                        ?.firstOrNull()
                        ?.text,
                ).joinToString(" · ").ifBlank { LaneGuidance.summary(navInfo) }
            laneImage.visibility = View.VISIBLE
            laneImage.setImageBitmap(signboardBitmap)
            return
        }
        val lanes = navInfo.currentStep?.lanes.orEmpty()
        lanePanel.visibility = if (lanes.isEmpty()) View.GONE else View.VISIBLE
        laneStatus.text = LaneGuidance.summary(navInfo)
        val laneBitmap = navInfo.currentStep?.lanesBitmap
        laneImage.visibility = if (laneBitmap == null) View.GONE else View.VISIBLE
        laneImage.setImageBitmap(laneBitmap)
    }

    private fun signboardWidthPx(): Int = resources.displayMetrics.widthPixels.coerceAtMost(MAX_SIGNBOARD_WIDTH_PX)

    private val terrainListener: (ElevationProfileSummary?) -> Unit = { summary ->
        latestTerrain = summary
        renderRoadAhead()
    }
    private val speedLimitAheadListener: (SpeedLimitAheadSummary?) -> Unit = { summary ->
        latestSpeedLimit = summary
        renderRoadAhead()
        renderSpeedCompliance()
    }
    private val routeRoadFeatureListener: (List<UpcomingRouteRoadFeature>) -> Unit = { upcoming ->
        val filtered =
            upcoming.filter {
                roadSignFilters.isEnabled(DrivingContext.DRIVING, it.point.type)
            }
        latestRoadFeatures = filtered
        renderRoadAhead()
        showRouteRoadFeatureMarkers(filtered)
    }
    private val routeCameraListener: (RouteCameraSnapshot) -> Unit = { snapshot ->
        latestCameraSnapshot = snapshot
        renderRoadAhead()
        showRouteCameraMarkers(snapshot.cameras)
    }

    /**
     * Drives the current-speed / speed-limit ring pair: red speed digit on
     * [com.roadpulse.auto.driving.SpeedComplianceLevel.OVER_LIMIT], amber breathing ring +
     * "Check speed" on [com.roadpulse.auto.driving.SpeedComplianceLevel.NEAR_LIMIT] - speed vs.
     * mapped limit only, no camera data. See [com.roadpulse.auto.driving.SpeedComplianceAdvisor].
     */
    private fun renderSpeedCompliance() {
        if (!::speedComplianceRing.isInitialized) return
        val summary = latestSpeedLimit
        val compliance =
            com.roadpulse.auto.driving.SpeedComplianceAdvisor.evaluate(
                summary?.currentSpeedKph,
                summary?.currentLimitKph,
            )
        speedComplianceRing.render(
            speedKph = compliance.speedKph,
            limitKph = compliance.limitKph,
            isOverLimit = compliance.level == com.roadpulse.auto.driving.SpeedComplianceLevel.OVER_LIMIT,
            showCheckSpeed =
                com.roadpulse.auto.driving.SpeedComplianceAdvisor
                    .shouldShowCheckSpeed(compliance.level),
        )
    }

    private val routeChangedListener =
        Navigator.RouteChangedListener {
            navigator?.let { refreshRouteIntelligence(it, force = true) }
            updateTripProgress()
        }
    private val tripProgressListener =
        Navigator.RemainingTimeOrDistanceChangedListener {
            runOnUiThread(::updateTripProgress)
        }
    private val promptVisibilityListener =
        PromptVisibilityChangedListener { promptVisible ->
            runOnUiThread {
                if (::roadAheadPanel.isInitialized) {
                    roadAheadPanel.visibility = if (promptVisible) View.INVISIBLE else View.VISIBLE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        routeRequestIssued = savedInstanceState?.getBoolean(STATE_ROUTE_REQUEST_ISSUED) == true
        if (!routeRequestIssued) {
            RouteRoadFeatureGuidance.clear()
            RouteCameraGuidance.clear()
        }
        status = findViewById(R.id.navigation_status)
        lanePanel = findViewById(R.id.lane_guidance_panel)
        laneStatus = findViewById(R.id.lane_guidance_status)
        laneImage = findViewById(R.id.lane_guidance_image)
        roadAheadEyebrow = findViewById(R.id.road_ahead_eyebrow)
        roadAheadPanel = findViewById(R.id.road_ahead_panel)
        roadAheadMeta = findViewById(R.id.road_ahead_meta)
        roadAheadPrimary = findViewById(R.id.road_ahead_primary)
        roadAheadSecondary = findViewById(R.id.road_ahead_secondary)
        speedComplianceRing = findViewById(R.id.speed_compliance_ring)
        val drivingOverlay = findViewById<View>(R.id.driving_overlay)
        val baseBottomPadding = drivingOverlay.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(drivingOverlay) { view, insets ->
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottomPadding + navigationBar)
            insets
        }
        renderRoadAhead()
        findViewById<Button>(R.id.stop_navigation).setOnClickListener {
            navigator?.stopGuidance()
            finish()
        }

        val selected = destination
        if (selected == null) {
            showStatus("Choose a destination on the My Maps home screen first.")
            return
        }
        status.text = "Preparing route to ${selected.title}…"
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            initializeNavigation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }
    }

    override fun onStart() {
        super.onStart()
        TurnByTurnState.addListener(laneListener)
        TerrainGuidance.addListener(terrainListener)
        SpeedLimitAheadGuidance.addListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.addListener(routeRoadFeatureListener)
        RouteCameraGuidance.addListener(routeCameraListener)
        registerDebugSpeedComplianceSimulator()
    }

    override fun onStop() {
        TurnByTurnState.removeListener(laneListener)
        TerrainGuidance.removeListener(terrainListener)
        SpeedLimitAheadGuidance.removeListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.removeListener(routeRoadFeatureListener)
        RouteCameraGuidance.removeListener(routeCameraListener)
        unregisterDebugSpeedComplianceSimulator()
        super.onStop()
    }

    /**
     * Debug-only QA hook: forces the speed-compliance ring into an arbitrary state so its
     * rendering (red digit, pulsing amber halo, "Check speed" text) can be verified on a real
     * device without needing live GPS motion or a real nearby camera. Never registered in a
     * non-debuggable build. Trigger with:
     * `adb shell am broadcast -a com.roadpulse.auto.DEBUG_SIMULATE_SPEED_COMPLIANCE
     *   --ei speedKph 140 --ei limitKph 100 --ez checkSpeed true`
     */
    private var debugSpeedComplianceReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugSpeedComplianceSimulator() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (debugSpeedComplianceReceiver != null) return
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (!::speedComplianceRing.isInitialized) return
                    val speedKph = intent.getIntExtra("speedKph", -1).takeIf { it >= 0 }
                    val limitKph = intent.getIntExtra("limitKph", -1).takeIf { it >= 0 }
                    val checkSpeed = intent.getBooleanExtra("checkSpeed", false)
                    speedComplianceRing.render(
                        speedKph = speedKph,
                        limitKph = limitKph,
                        isOverLimit = speedKph != null && limitKph != null && speedKph > limitKph,
                        showCheckSpeed = checkSpeed,
                    )
                }
            }
        // Exported so `adb shell am broadcast` (a different UID) can reach it - only ever
        // registered in a debuggable build, never shipped in a release APK.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            receiver,
            android.content.IntentFilter("com.roadpulse.auto.DEBUG_SIMULATE_SPEED_COMPLIANCE"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        debugSpeedComplianceReceiver = receiver
    }

    private fun unregisterDebugSpeedComplianceSimulator() {
        debugSpeedComplianceReceiver?.let(::unregisterReceiver)
        debugSpeedComplianceReceiver = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ROUTE_REQUEST_ISSUED, routeRequestIssued)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initializeNavigation()
        } else {
            showStatus("Location permission is required for navigation.")
        }
    }

    private fun initializeNavigation() {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    readyNavigator.addArrivalListener(arrivalListener)
                    readyNavigator.removeRouteChangedListener(routeChangedListener)
                    readyNavigator.addRouteChangedListener(routeChangedListener)
                    readyNavigator.removeRemainingTimeOrDistanceChangedListener(tripProgressListener)
                    readyNavigator.addRemainingTimeOrDistanceChangedListener(
                        30,
                        100,
                        tripProgressListener,
                    )
                    LaneGuidance.register(this@NavigationActivity, readyNavigator)
                    val fragment =
                        supportFragmentManager.findFragmentById(R.id.navigation_fragment)
                            as SupportNavigationFragment
                    navigationFragment = fragment
                    fragment.removePromptVisibilityChangedListener(promptVisibilityListener)
                    fragment.addPromptVisibilityChangedListener(promptVisibilityListener)
                    fragment.setSpeedLimitIconEnabled(true)
                    fragment.setSpeedometerEnabled(true)
                    fragment.setEtaCardEnabled(false)
                    if (
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        fragment.getMapAsync { map ->
                            navigationMap = map
                            RoadPulseMapTheme.apply(this@NavigationActivity, map)
                            map.followMyLocation(CameraPerspective.TILTED)
                            showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                            showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                            map.setOnCameraIdleListener {
                                showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                                showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                            }
                        }
                    }
                    calculateRoute(readyNavigator)
                }

                override fun onError(errorCode: Int) {
                    val message =
                        when (errorCode) {
                            NavigationApi.ErrorCode.NOT_AUTHORIZED ->
                                "Google Navigation is not authorized for this app key."
                            NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED ->
                                "Accept the Google Navigation terms to continue."
                            NavigationApi.ErrorCode.NETWORK_ERROR ->
                                "A network error prevented Navigation from starting."
                            NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING ->
                                "Location permission is required for navigation."
                            else -> "Google Navigation could not start (error $errorCode)."
                        }
                    showStatus(message)
                }
            },
        )
    }

    private fun calculateRoute(readyNavigator: Navigator) {
        if (routeRequestIssued) {
            status.text = destination?.let { "Navigation active for ${it.title}" }
                ?: "Navigation active"
            return
        }
        val selected = destination ?: return
        val waypoint =
            runCatching {
                Waypoint
                    .builder()
                    .setPlaceIdString(selected.placeId)
                    .setTitle(selected.title)
                    .build()
            }.getOrElse {
                showStatus("This selected place cannot be used as a driving destination.")
                return
            }
        val routingOptions =
            RoutingOptions().apply {
                travelMode(RoutingOptions.TravelMode.DRIVING)
                avoidHighways(RouteStylePreferencesStore(this@NavigationActivity).load() == RouteStyle.SCENIC)
            }
        when (GoogleUsageGuard(this).navigationDestinations.tryConsume()) {
            is QuotaDecision.Blocked -> {
                showStatus("The 1,000-destination monthly safety limit is reached.")
                return
            }
            is QuotaDecision.Allowed -> routeRequestIssued = true
        }
        routeStopOptimizer.setRoute(
            navigator = readyNavigator,
            finalDestination = waypoint,
            routingOptions = routingOptions,
            onProgress = { message -> runOnUiThread { status.text = message } },
        ) { plan ->
            runOnUiThread {
                if (plan.status == Navigator.RouteStatus.OK) {
                    readyNavigator.setAudioGuidance(
                        Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE,
                    )
                    readyNavigator.startGuidance()
                    status.text = plan.summary()?.let {
                        "$it\nThen: ${selected.title}"
                    } ?: "Navigating to ${selected.title}"
                    clearRouteIntelligence()
                    refreshRouteIntelligence(readyNavigator, force = true)
                } else {
                    showStatus("Route unavailable: ${plan.status.name.replace('_', ' ').lowercase()}.")
                }
            }
        }
    }

    override fun onDestroy() {
        routeRoadFeatureMarkers.forEach(Marker::remove)
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = emptyList()
        routeCameraMarkers.forEach(Marker::remove)
        routeCameraMarkers.clear()
        routeCameraMarkerIds = emptyList()
        navigationMap = null
        navigationFragment?.removePromptVisibilityChangedListener(promptVisibilityListener)
        navigationFragment = null
        navigator?.removeArrivalListener(arrivalListener)
        navigator?.removeRouteChangedListener(routeChangedListener)
        navigator?.removeRemainingTimeOrDistanceChangedListener(tripProgressListener)
        super.onDestroy()
    }

    private fun clearRouteIntelligence() {
        latestTerrain = null
        latestSpeedLimit = null
        latestRoadFeatures = emptyList()
        latestCameraSnapshot = RouteCameraSnapshot()
        TerrainGuidance.clear()
        SpeedLimitAheadGuidance.clear()
        RouteRoadFeatureGuidance.clear()
        RouteCameraGuidance.clear()
        renderRoadAhead()
    }

    private fun refreshRouteIntelligence(
        readyNavigator: Navigator,
        force: Boolean,
    ) {
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.TERRAIN)) {
            TerrainGuidance.refresh(this, readyNavigator, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_LIMIT_AHEAD)) {
            SpeedLimitAheadGuidance.refresh(this, readyNavigator, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.ROAD_SIGNS)) {
            RouteRoadFeatureGuidance.refresh(this, readyNavigator, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_CAMERAS)) {
            RouteCameraGuidance.refresh(this, readyNavigator, force)
        }
    }

    private fun renderRoadAhead() {
        if (!::roadAheadPrimary.isInitialized) return
        val presentation =
            DrivingAttention.build(
                speedLimit = latestSpeedLimit,
                roadFeatures = latestRoadFeatures,
                cameras = latestCameraSnapshot,
                terrain = latestTerrain,
                maneuverDistanceMeters = maneuverDistanceMeters,
            )
        roadAheadPrimary.text = presentation.primaryText
        roadAheadSecondary.text = presentation.secondaryText
        roadAheadSecondary.visibility = if (presentation.secondaryText == null) View.GONE else View.VISIBLE
        roadAheadEyebrow.text =
            when (presentation.primary?.type) {
                RoadAheadEventType.ROAD_CONTROL -> "ROAD CONTROL"
                RoadAheadEventType.SPEED_CHANGE -> "SPEED AHEAD"
                RoadAheadEventType.CAMERA -> "ROUTE CAMERA"
                RoadAheadEventType.TERRAIN -> "TERRAIN AHEAD"
                null -> "ROAD AHEAD"
            }
        roadAheadMeta.text =
            when (presentation.primary?.confidence) {
                RouteMatchConfidence.HIGH -> "HIGH CONFIDENCE"
                RouteMatchConfidence.MEDIUM -> "ROUTE MATCHED"
                null -> "ADAPTIVE VIEW"
            }
    }

    private fun updateTripProgress() {
        val trip = navigator?.currentTimeAndDistance ?: return
        if (trip.meters <= 0 || trip.seconds <= 0) return
        val distance =
            if (trip.meters >= 10_000) {
                "${trip.meters / 1_000} km"
            } else if (trip.meters >= 1_000) {
                String.format(Locale.GERMANY, "%.1f km", trip.meters / 1_000.0)
            } else {
                "${trip.meters} m"
            }
        val hours = trip.seconds / 3_600
        val minutes = (trip.seconds % 3_600 + 59) / 60
        val duration = if (hours > 0) "${hours}h ${minutes}m" else "$minutes min"
        val arrival =
            android.text.format.DateFormat.getTimeFormat(this).format(
                Date(System.currentTimeMillis() + trip.seconds * 1_000L),
            )
        status.text = "$distance · $duration · arrive $arrival"
    }

    private fun showRouteRoadFeatureMarkers(upcoming: List<UpcomingRouteRoadFeature>) {
        val map = navigationMap ?: return
        val visible = routeFeaturesInsideVisibleMap(map, upcoming)
        val pointIds = visible.map { it.point.id }
        if (pointIds == routeRoadFeatureMarkerIds) return
        routeRoadFeatureMarkers.forEach(Marker::remove)
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = pointIds
        visible.forEach { sign ->
            val point = sign.point
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                        .title(point.title)
                        .snippet("On your route · ${sign.compactText()} · ${point.detail}")
                        .icon(mapMarkerIcons.infrastructure(point))
                        .anchor(.5f, .5f)
                        .zIndex(8f),
                )?.let(routeRoadFeatureMarkers::add)
        }
    }

    private fun routeFeaturesInsideVisibleMap(
        map: GoogleMap,
        upcoming: List<UpcomingRouteRoadFeature>,
    ): List<UpcomingRouteRoadFeature> {
        val bounds =
            runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
                ?: return upcoming
        return upcoming.filter { sign ->
            val coordinate = sign.point.coordinate
            coordinate.latitude in bounds.southwest.latitude..bounds.northeast.latitude &&
                if (bounds.southwest.longitude <= bounds.northeast.longitude) {
                    coordinate.longitude in bounds.southwest.longitude..bounds.northeast.longitude
                } else {
                    coordinate.longitude >= bounds.southwest.longitude ||
                        coordinate.longitude <= bounds.northeast.longitude
                }
        }
    }

    private fun showRouteCameraMarkers(upcoming: List<UpcomingRouteCamera>) {
        val map = navigationMap ?: return
        val visible =
            upcoming.filter { camera ->
                coordinateIsInsideVisibleMap(
                    map,
                    camera.camera.poi.latitude,
                    camera.camera.poi.longitude,
                )
            }
        val ids = visible.map(UpcomingRouteCamera::id)
        if (ids == routeCameraMarkerIds) return
        routeCameraMarkers.forEach(Marker::remove)
        routeCameraMarkers.clear()
        routeCameraMarkerIds = ids
        visible.forEach { upcomingCamera ->
            val camera = upcomingCamera.camera.poi
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(camera.latitude, camera.longitude))
                        .title(upcomingCamera.compactText())
                        .snippet(upcomingCamera.camera.sources.joinToString(" + ") { it.displayName })
                        .icon(mapMarkerIcons.camera(camera.type, camera.speedLimitKph))
                        .anchor(.5f, .5f)
                        .zIndex(9f),
                )?.let(routeCameraMarkers::add)
        }
    }

    private fun coordinateIsInsideVisibleMap(
        map: GoogleMap,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        val bounds =
            runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
                ?: return true
        return latitude in bounds.southwest.latitude..bounds.northeast.latitude &&
            if (bounds.southwest.longitude <= bounds.northeast.longitude) {
                longitude in bounds.southwest.longitude..bounds.northeast.longitude
            } else {
                longitude >= bounds.southwest.longitude || longitude <= bounds.northeast.longitude
            }
    }

    private fun showStatus(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 2001
        private const val STATE_ROUTE_REQUEST_ISSUED = "route_request_issued"
        private const val MAX_SIGNBOARD_WIDTH_PX = 900
        private const val SIGNBOARD_HEIGHT_PX = 260
    }
}
