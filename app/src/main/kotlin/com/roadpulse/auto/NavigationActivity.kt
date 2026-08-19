package com.roadpulse.auto

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
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
import com.roadpulse.auto.engine.GraphHopperGuidanceEngine
import com.roadpulse.auto.engine.GraphHopperRoutingEngine
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.RegionInstallStore
import com.roadpulse.auto.engine.Route
import com.roadpulse.auto.map.MapLibreMapController
import com.roadpulse.auto.map.MapMarker
import com.roadpulse.auto.map.MapMarkerIconFactory
import com.roadpulse.auto.map.MapPolyline
import com.roadpulse.auto.map.RoadPulseMapLibreStyle
import com.roadpulse.auto.settings.DisplayFilterStore
import com.roadpulse.auto.settings.DisplayLayer
import com.roadpulse.auto.settings.DrivingContext
import com.roadpulse.auto.settings.RoadSignFilterStore
import com.roadpulse.auto.settings.RouteStyle
import com.roadpulse.auto.settings.RouteStylePreferencesStore
import com.roadpulse.auto.signage.SignboardGuidanceEngine
import com.roadpulse.auto.signage.SignboardRenderer
import com.roadpulse.auto.stops.RouteStopOptimizer
import com.roadpulse.auto.terrain.ElevationProfileSummary
import com.roadpulse.auto.terrain.TerrainGuidance
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.voice.VoiceGuidance
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Free-stack turn-by-turn navigation: GraphHopper routing + on-device guidance (map-matching,
 * ETA, off-route detection - [GraphHopperGuidanceEngine]), MapLibre rendering, and Android
 * `TextToSpeech` voice prompts ([VoiceGuidance]), replacing Google Navigation SDK's `Navigator`
 * entirely. See ZERO_COST_ARCHITECTURE.md.
 *
 * Route-line/puck rendering and GPS-driven guidance had no existing app code to port - Google's
 * `SupportNavigationFragment` drew all of that internally. This class owns GPS updates directly
 * via `LocationManager` (`Navigator` previously did this itself), feeding every fix to the
 * guidance engine, the map's location puck, and the camera.
 */
class NavigationActivity : FragmentActivity() {
    private lateinit var mapView: MapView
    private var tileServer: com.roadpulse.auto.engine.LocalMbtilesServer? = null
    private var mapController: MapLibreMapController? = null
    private var routePolyline: MapPolyline? = null
    private var destinationMarker: MapMarker? = null
    private var locationManager: LocationManager? = null
    private var locationProvider: String? = null
    private lateinit var voiceGuidance: VoiceGuidance
    private val regionInstallStore by lazy { RegionInstallStore(applicationContext) }
    private val routingEngine by lazy { GraphHopperRoutingEngine(regionInstallStore) }
    private val guidanceEngine by lazy { GraphHopperGuidanceEngine(routingEngine) }
    private var activeRoute: Route? = null
    private var activeRegionId: String? = null
    private var lastKnownCoordinate: RoadCoordinate? = null

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
    private val routeRoadFeatureMarkers = mutableListOf<MapMarker>()
    private var routeRoadFeatureMarkerIds: List<String> = emptyList()
    private val routeCameraMarkers = mutableListOf<MapMarker>()
    private var routeCameraMarkerIds: List<String> = emptyList()
    private val mapMarkerIcons by lazy { MapMarkerIconFactory(this) }
    private val destination by lazy { SelectedDestinationStore(this).load() }
    private var routeRequestIssued = false
    private val routeStopOptimizer by lazy { RouteStopOptimizer(this) }
    private val displayFilters by lazy { DisplayFilterStore(this) }
    private val roadSignFilters by lazy { RoadSignFilterStore(this) }

    private val locationListener =
        LocationListener { location -> onLocationUpdate(location) }

    private val guidanceListener: (GuidanceState) -> Unit = { state ->
        runOnUiThread {
            voiceGuidance.onGuidanceState(state)
            maneuverDistanceMeters = state.distanceToNextManeuverMeters
            renderRoadAhead()
            renderLaneAndSignboardPanel(state)
            updateTripProgress(state)
            activeRoute?.let { refreshRouteIntelligence(it, force = false) }
        }
    }

    /**
     * Prefers RoadPulse's own signboard rendering (Autobahn exit + destinations + lane states)
     * when [SignboardGuidanceEngine] has reliable enough data for it. Unlike the Google-based
     * version this replaces, there is no per-lane bitmap/text fallback to fall back to -
     * GraphHopper has no per-lane data of its own (see `SignboardGuidanceEngine`'s
     * `GuidanceState`-based `build` overload) - so the lane panel simply hides when there's
     * nothing reliable to show, rather than showing degraded Google content that no longer exists.
     */
    private fun renderLaneAndSignboardPanel(state: GuidanceState) {
        val signboardGuidance =
            SignboardGuidanceEngine.build(
                state,
                latestRoadFeatures,
                RouteRoadFeatureGuidance.latestLaneTopologySections,
            )
        val signboardBitmap =
            SignboardRenderer.render(
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
                ).joinToString(" · ").ifBlank { state.currentStep?.instructionText.orEmpty() }
            laneImage.visibility = View.VISIBLE
            laneImage.setImageBitmap(signboardBitmap)
        } else {
            lanePanel.visibility = View.GONE
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        routeRequestIssued = savedInstanceState?.getBoolean(STATE_ROUTE_REQUEST_ISSUED) == true
        if (!routeRequestIssued) {
            RouteRoadFeatureGuidance.clear()
            RouteCameraGuidance.clear()
        }
        voiceGuidance = VoiceGuidance(this)
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
            stopNavigating()
            finish()
        }

        MapLibre.getInstance(this)
        mapView = findViewById(R.id.navigation_map_view)
        mapView.onCreate(savedInstanceState)

        val selected = destination
        if (selected == null || selected.latitude == null || selected.longitude == null) {
            showStatus("Choose a destination on the My Maps home screen first.")
            return
        }
        status.text = "Preparing route to ${selected.title}…"
        setupMap()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }
    }

    private fun setupMap() {
        CompletableFuture
            .supplyAsync { startTileServerForBestRegion() }
            .thenAccept { server ->
                tileServer = server
                runOnUiThread { server?.let { loadMap(it.port) } ?: showStatus("No offline map downloaded for this area yet.") }
            }
    }

    /** Picks whichever installed region covers the device's last-known location, falling back to
     * the first installed region if location isn't available yet - mirrors `MainActivity`'s
     * identical logic. Runs off the main thread. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startTileServerForBestRegion(): com.roadpulse.auto.engine.LocalMbtilesServer? {
        val coordinate =
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val manager = getSystemService(LocationManager::class.java)
                sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxByOrNull(Location::getTime)
                    ?.let { RoadCoordinate(it.latitude, it.longitude) }
            } else {
                null
            }
        val region =
            coordinate?.let(regionInstallStore::regionContaining)
                ?: regionInstallStore.installedRegions().firstOrNull()
                ?: return null
        activeRegionId = region.id
        return com.roadpulse.auto.engine
            .LocalMbtilesServer(region.tilesFile)
            .apply { start() }
    }

    /** Same region-boundary handling as `MainActivity.switchActiveRegionIfNeeded` - see its doc
     * comment for why a brief reload on crossing a region boundary is an accepted v1 trade-off. */
    private fun switchActiveRegionIfNeeded(controller: MapLibreMapController) {
        val center = controller.cameraTarget() ?: return
        val active = activeRegionId?.let(regionInstallStore::region)
        if (active != null && active.bounds.contains(center)) return
        val next = regionInstallStore.regionContaining(center) ?: return
        if (next.id == active?.id) return
        tileServer?.stop()
        activeRegionId = next.id
        CompletableFuture
            .supplyAsync {
                com.roadpulse.auto.engine
                    .LocalMbtilesServer(next.tilesFile)
                    .apply { start() }
            }.thenAccept { server ->
                tileServer = server
                runOnUiThread { loadMap(server.port) }
            }
    }

    private fun loadMap(port: Int) {
        val styleJson = RoadPulseMapLibreStyle.styleJson(this, port)
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                val controller = MapLibreMapController(mapView, map, style)
                mapController = controller
                // The first GPS fix (synchronous getLastKnownLocation) almost always arrives
                // before this async style load finishes, so its animateCameraTo call in
                // onLocationUpdate silently no-ops on a null controller. Without this, a
                // stationary device (no further fix until a 3m move) is stuck at MapLibre's
                // default near-zero zoom for the rest of the navigation session.
                lastKnownCoordinate?.let { controller.animateCameraTo(it, DRIVING_ZOOM) }
                activeRoute?.let(::drawRoutePolyline)
                showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                controller.setOnCameraIdleListener {
                    switchActiveRegionIfNeeded(controller)
                    showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                    showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        guidanceEngine.addListener(guidanceListener)
        TerrainGuidance.addListener(terrainListener)
        SpeedLimitAheadGuidance.addListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.addListener(routeRoadFeatureListener)
        RouteCameraGuidance.addListener(routeCameraListener)
        registerDebugSpeedComplianceSimulator()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        guidanceEngine.removeListener(guidanceListener)
        TerrainGuidance.removeListener(terrainListener)
        SpeedLimitAheadGuidance.removeListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.removeListener(routeRoadFeatureListener)
        RouteCameraGuidance.removeListener(routeCameraListener)
        unregisterDebugSpeedComplianceSimulator()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
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
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            showStatus("Location permission is required for navigation.")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val manager = getSystemService(LocationManager::class.java)
        locationManager = manager
        val provider =
            when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
        if (provider == null) {
            showStatus("Turn on phone location to navigate.")
            return
        }
        locationProvider = provider
        manager.getLastKnownLocation(provider)?.let(::onLocationUpdate)
        manager.requestLocationUpdates(provider, LOCATION_UPDATE_INTERVAL_MILLIS, LOCATION_UPDATE_MIN_METERS, locationListener)
    }

    private fun onLocationUpdate(location: Location) {
        val coordinate = RoadCoordinate(location.latitude, location.longitude)
        lastKnownCoordinate = coordinate
        guidanceEngine.onLocationUpdate(
            coordinate,
            speedKph = if (location.hasSpeed()) location.speed * 3.6f else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
        )
        val controller = mapController
        if (controller != null) {
            controller.registerIcon(MY_LOCATION_ICON_ID, myLocationPuckBitmap())
            controller.setMyLocationPuck(coordinate, location.bearing, MY_LOCATION_ICON_ID)
            controller.animateCameraTo(coordinate, DRIVING_ZOOM)
        }
        if (!routeRequestIssued) calculateRoute(coordinate)
    }

    private fun myLocationPuckBitmap(): android.graphics.Bitmap {
        val size = (24 * resources.displayMetrics.density).toInt()
        val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(33, 150, 243)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (4 * resources.displayMetrics.density), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (4 * resources.displayMetrics.density), paint)
        return bitmap
    }

    private fun calculateRoute(origin: RoadCoordinate) {
        if (routeRequestIssued) return
        val selected = destination ?: return
        val destinationLatitude = selected.latitude ?: return
        val destinationLongitude = selected.longitude ?: return
        val destCoordinate = RoadCoordinate(destinationLatitude, destinationLongitude)
        routeRequestIssued = true
        val avoidHighways = RouteStylePreferencesStore(this).load() == RouteStyle.SCENIC
        routeStopOptimizer.setRoute(
            routingEngine = routingEngine,
            origin = origin,
            destination = destCoordinate,
            avoidHighways = avoidHighways,
            onProgress = { message -> runOnUiThread { status.text = message } },
        ) { plan ->
            runOnUiThread {
                val route = plan.route
                if (route != null) {
                    activeRoute = route
                    guidanceEngine.startGuidance(route)
                    drawRoutePolyline(route)
                    showDestinationMarker(destCoordinate)
                    status.text = plan.summary()?.let {
                        "$it\nThen: ${selected.title}"
                    } ?: "Navigating to ${selected.title}"
                    clearRouteIntelligence()
                    refreshRouteIntelligence(route, force = true)
                } else {
                    routeRequestIssued = false
                    showStatus(plan.summary() ?: "Route unavailable: ${plan.status.name.replace('_', ' ').lowercase()}.")
                }
            }
        }
    }

    private fun drawRoutePolyline(route: Route) {
        val controller = mapController ?: return
        routePolyline?.let(controller::removePolyline)
        routePolyline = controller.addPolyline(route.geometry, ROUTE_LINE_COLOR_HEX, widthDp = 6f)
    }

    private fun showDestinationMarker(coordinate: RoadCoordinate) {
        val controller = mapController ?: return
        destinationMarker?.let(controller::removeMarker)
        controller.registerIcon(DESTINATION_ICON_ID, destinationPinBitmap())
        destinationMarker = controller.addMarker(coordinate, DESTINATION_ICON_ID)
    }

    private fun destinationPinBitmap(): android.graphics.Bitmap {
        val size = (36 * resources.displayMetrics.density).toInt()
        val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(30, 136, 229)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (3 * resources.displayMetrics.density), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 3 * resources.displayMetrics.density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (3 * resources.displayMetrics.density), paint)
        return bitmap
    }

    private fun stopNavigating() {
        guidanceEngine.stopGuidance()
        locationManager?.removeUpdates(locationListener)
        voiceGuidance.shutdown()
    }

    override fun onDestroy() {
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = emptyList()
        routeCameraMarkers.clear()
        routeCameraMarkerIds = emptyList()
        locationManager?.removeUpdates(locationListener)
        guidanceEngine.stopGuidance()
        voiceGuidance.shutdown()
        mapView.onDestroy()
        tileServer?.stop()
        mapController = null
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
        route: Route,
        force: Boolean,
    ) {
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.TERRAIN)) {
            TerrainGuidance.refresh(this, route, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_LIMIT_AHEAD)) {
            SpeedLimitAheadGuidance.refresh(this, route, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.ROAD_SIGNS)) {
            RouteRoadFeatureGuidance.refresh(this, route, force)
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_CAMERAS)) {
            RouteCameraGuidance.refresh(this, route, force)
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

    private fun updateTripProgress(state: GuidanceState) {
        if (state.isRerouting) {
            status.text = "Rerouting…"
            return
        }
        if (state.hasArrived) {
            status.text = "Arrived at ${destination?.title ?: "destination"}"
            return
        }
        val meters = state.distanceToDestinationMeters ?: return
        if (meters <= 0) return
        val distance =
            if (meters >= 10_000) {
                "${meters / 1_000} km"
            } else if (meters >= 1_000) {
                String.format(Locale.GERMANY, "%.1f km", meters / 1_000.0)
            } else {
                "$meters m"
            }
        val arrival =
            state.etaEpochSeconds?.let {
                android.text.format.DateFormat
                    .getTimeFormat(this)
                    .format(Date(it * 1_000L))
            } ?: "--"
        status.text = "$distance · arrive $arrival"
    }

    private fun showRouteRoadFeatureMarkers(upcoming: List<UpcomingRouteRoadFeature>) {
        val controller = mapController ?: return
        val visible = routeFeaturesInsideVisibleMap(controller, upcoming)
        val pointIds = visible.map { it.point.id }
        if (pointIds == routeRoadFeatureMarkerIds) return
        routeRoadFeatureMarkers.forEach(controller::removeMarker)
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = pointIds
        visible.forEach { sign ->
            val point = sign.point
            val (iconId, bitmap) = mapMarkerIcons.infrastructure(point)
            controller.registerIcon(iconId, bitmap)
            routeRoadFeatureMarkers += controller.addMarker(point.coordinate, iconId)
        }
    }

    private fun routeFeaturesInsideVisibleMap(
        controller: MapLibreMapController,
        upcoming: List<UpcomingRouteRoadFeature>,
    ): List<UpcomingRouteRoadFeature> {
        val bounds = runCatching { controller.visibleBounds() }.getOrNull() ?: return upcoming
        return upcoming.filter { sign -> bounds.contains(sign.point.coordinate) }
    }

    private fun showRouteCameraMarkers(upcoming: List<UpcomingRouteCamera>) {
        val controller = mapController ?: return
        val bounds = runCatching { controller.visibleBounds() }.getOrNull()
        val visible =
            upcoming.filter { camera ->
                bounds == null ||
                    bounds.contains(RoadCoordinate(camera.camera.poi.latitude, camera.camera.poi.longitude))
            }
        val ids = visible.map(UpcomingRouteCamera::id)
        if (ids == routeCameraMarkerIds) return
        routeCameraMarkers.forEach(controller::removeMarker)
        routeCameraMarkers.clear()
        routeCameraMarkerIds = ids
        visible.forEach { upcomingCamera ->
            val camera = upcomingCamera.camera.poi
            val (iconId, bitmap) = mapMarkerIcons.camera(camera.type, camera.speedLimitKph)
            controller.registerIcon(iconId, bitmap)
            routeCameraMarkers += controller.addMarker(RoadCoordinate(camera.latitude, camera.longitude), iconId)
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
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val LOCATION_UPDATE_MIN_METERS = 3f
        private const val DRIVING_ZOOM = 17.0
        private const val DESTINATION_ICON_ID = "nav-destination-pin"
        private const val MY_LOCATION_ICON_ID = "nav-my-location-puck"
        private const val ROUTE_LINE_COLOR_HEX = "#4F7CFF"
    }
}
