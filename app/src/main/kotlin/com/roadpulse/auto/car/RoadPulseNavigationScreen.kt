package com.roadpulse.auto.car

import android.Manifest
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roadpulse.auto.R
import com.roadpulse.auto.destination.SelectedDestinationStore
import com.roadpulse.auto.driving.DrivingAttention
import com.roadpulse.auto.driving.RouteCameraGuidance
import com.roadpulse.auto.driving.RouteCameraSnapshot
import com.roadpulse.auto.driving.RouteRoadFeatureGuidance
import com.roadpulse.auto.driving.SpeedLimitAheadGuidance
import com.roadpulse.auto.driving.SpeedLimitAheadSummary
import com.roadpulse.auto.driving.UpcomingRouteCamera
import com.roadpulse.auto.driving.UpcomingRouteRoadFeature
import com.roadpulse.auto.engine.GraphHopperGuidanceEngine
import com.roadpulse.auto.engine.GraphHopperRoutingEngine
import com.roadpulse.auto.engine.GuidanceState
import com.roadpulse.auto.engine.LocalMbtilesServer
import com.roadpulse.auto.engine.RegionInstallStore
import com.roadpulse.auto.engine.Route
import com.roadpulse.auto.map.MapLibreMapController
import com.roadpulse.auto.map.MapMarker
import com.roadpulse.auto.map.MapMarkerIconFactory
import com.roadpulse.auto.map.MapPolyline
import com.roadpulse.auto.map.RoadPulseMapLibreStyle
import com.roadpulse.auto.map.SpeedLimitRoadStyle
import com.roadpulse.auto.settings.DisplayFilterStore
import com.roadpulse.auto.settings.DisplayLayer
import com.roadpulse.auto.settings.DrivingContext
import com.roadpulse.auto.settings.RoadSignFilterStore
import com.roadpulse.auto.settings.RouteStyle
import com.roadpulse.auto.settings.RouteStylePreferencesStore
import com.roadpulse.auto.stops.RouteStopOptimizer
import com.roadpulse.auto.stops.RouteStopPreferencesStore
import com.roadpulse.auto.terrain.ElevationProfileSummary
import com.roadpulse.auto.terrain.TerrainGuidance
import com.roadpulse.auto.traffic.AutobahnFacilityRepository
import com.roadpulse.auto.traffic.AutobahnTrafficRepository
import com.roadpulse.auto.traffic.DwdRoadWeatherRepository
import com.roadpulse.auto.traffic.OpenChargeMapRepository
import com.roadpulse.auto.traffic.OpenStreetMapRoadInfrastructureRepository
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadFacility
import com.roadpulse.auto.traffic.RoadFacilityType
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import com.roadpulse.auto.traffic.RoadWeatherResult
import com.roadpulse.auto.traffic.TrafficEventResult
import com.roadpulse.auto.traffic.TrafficEventType
import com.roadpulse.auto.traffic.TrafficSnapshotStore
import com.roadpulse.auto.traffic.WeatherWarning
import com.roadpulse.auto.traffic.displayName
import com.roadpulse.auto.voice.VoiceGuidance
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.CompletableFuture

/**
 * Android Auto surface backed by the free stack: GraphHopper routing/guidance + MapLibre
 * rendering + `TextToSpeech` voice, replacing Google Navigation SDK's `NavigationViewForAuto`/
 * `Navigator` entirely - the same pattern as `NavigationActivity`'s migration, adapted for the
 * `Screen`/`SurfaceCallback`/`VirtualDisplay` model Android Auto projection uses instead of a
 * normal `Activity` view hierarchy. Owns its own `GraphHopperRoutingEngine`/
 * `GraphHopperGuidanceEngine`/`VoiceGuidance`/GPS-update instances, independent of
 * `NavigationActivity`'s - matching this class's own pre-migration independence (it acquired its
 * own `Navigator` session rather than sharing `NavigationActivity`'s), since the phone and car
 * screens are used alternatively in practice, not concurrently (see `DrivingSessionState`).
 *
 * Real German enforcement points are deliberately not loaded in this class. The projected
 * experience contains the free-stack route map plus navigation and generic road-safety
 * information.
 */
class RoadPulseNavigationScreen(
    carContext: CarContext,
) : Screen(carContext),
    SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var tileServer: LocalMbtilesServer? = null
    private var mapController: MapLibreMapController? = null
    private var routePolyline: MapPolyline? = null
    private var destinationMarker: MapMarker? = null
    private var speedComplianceRing: com.roadpulse.auto.driving.SpeedComplianceRingView? = null
    private var locationManager: LocationManager? = null
    private val voiceGuidance = VoiceGuidance(carContext)
    private val regionInstallStore by lazy { RegionInstallStore(carContext.applicationContext) }
    private val routingEngine by lazy { GraphHopperRoutingEngine(regionInstallStore) }
    private val guidanceEngine by lazy { GraphHopperGuidanceEngine(routingEngine) }
    private var activeRoute: Route? = null
    private var activeRegionId: String? = null
    private var guidanceRunning = false
    private val routeStopPreferences = RouteStopPreferencesStore(carContext)
    private val routeStopOptimizer = RouteStopOptimizer(carContext)
    private val displayFilters = DisplayFilterStore(carContext)
    private val roadSignFilters = RoadSignFilterStore(carContext)
    private val carNavigationManager = carContext.getCarService(NavigationManager::class.java)
    private var carNavigationStarted = false
    private val trafficMarkers = mutableListOf<MapMarker>()
    private val routeRoadFeatureMarkers = mutableListOf<MapMarker>()
    private var routeRoadFeatureMarkerIds: List<String> = emptyList()
    private val routeCameraMarkers = mutableListOf<MapMarker>()
    private var routeCameraMarkerIds: List<String> = emptyList()
    private val trafficLines = mutableListOf<MapPolyline>()
    private val roadInfrastructureRepository =
        OpenStreetMapRoadInfrastructureRepository(carContext)
    private val autobahnTrafficRepository = AutobahnTrafficRepository(carContext)
    private val autobahnFacilityRepository = AutobahnFacilityRepository(carContext)
    private val openChargeMapRepository = OpenChargeMapRepository(carContext)
    private val dwdRoadWeatherRepository = DwdRoadWeatherRepository(carContext)
    private val mapMarkerIcons = MapMarkerIconFactory(carContext)
    private var trafficRefreshInProgress = false
    private var roadIntelligenceSummary = ""
    private var statusTitle = "My Maps ready"
    private var statusDetail = "Choose a destination on the phone, then press Start here."
    private var routingInfo: RoutingInfo? = null
    private var latestTerrain: ElevationProfileSummary? = null
    private var latestSpeedLimit: SpeedLimitAheadSummary? = null
    private var latestRoadFeatures: List<UpcomingRouteRoadFeature> = emptyList()
    private var latestCameraSnapshot = RouteCameraSnapshot()

    private val locationListener = LocationListener { location -> onLocationUpdate(location) }

    private val guidanceListener: (GuidanceState) -> Unit = { state ->
        rebuildRoutingInfo(state)
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.TERRAIN)) {
            activeRoute?.let { TerrainGuidance.refresh(carContext, it) }
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_LIMIT_AHEAD)) {
            activeRoute?.let { SpeedLimitAheadGuidance.refresh(carContext, it) }
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.ROAD_SIGNS)) {
            activeRoute?.let { RouteRoadFeatureGuidance.refresh(carContext, it) }
        }
        if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_CAMERAS)) {
            activeRoute?.let { RouteCameraGuidance.refresh(carContext, it) }
        }
        voiceGuidance.onGuidanceState(state)
        when {
            state.isRerouting -> {
                statusTitle = "Finding a new route"
                statusDetail = "Guidance will return with the new route."
            }
            state.hasArrived -> {
                routingInfo = null
                statusTitle = "Arrived"
            }
        }
        invalidate()
    }
    private val terrainListener: (ElevationProfileSummary?) -> Unit = { summary ->
        latestTerrain = summary
        invalidate()
    }
    private val speedLimitAheadListener: (SpeedLimitAheadSummary?) -> Unit = { summary ->
        latestSpeedLimit = summary
        renderSpeedCompliance()
        invalidate()
    }
    private val routeRoadFeatureListener: (List<UpcomingRouteRoadFeature>) -> Unit = { upcoming ->
        val filtered =
            upcoming.filter {
                roadSignFilters.isEnabled(DrivingContext.DRIVING, it.point.type)
            }
        latestRoadFeatures = filtered
        showRouteRoadFeatureMarkers(filtered)
        invalidate()
    }
    private val routeCameraListener: (RouteCameraSnapshot) -> Unit = { snapshot ->
        latestCameraSnapshot = snapshot
        showRouteCameraMarkers(snapshot.cameras)
        invalidate()
    }

    /** Mirrors NavigationActivity's ring: red speed digit over the limit, amber breathing ring +
     * "Check speed" on [com.roadpulse.auto.driving.SpeedComplianceLevel.NEAR_LIMIT] - speed vs.
     * mapped limit only, no camera data. See [com.roadpulse.auto.driving.SpeedComplianceAdvisor]. */
    private fun renderSpeedCompliance() {
        val ring = speedComplianceRing ?: return
        val compliance =
            com.roadpulse.auto.driving.SpeedComplianceAdvisor.evaluate(
                latestSpeedLimit?.currentSpeedKph,
                latestSpeedLimit?.currentLimitKph,
            )
        ring.render(
            speedKph = compliance.speedKph,
            limitKph = compliance.limitKph,
            isOverLimit = compliance.level == com.roadpulse.auto.driving.SpeedComplianceLevel.OVER_LIMIT,
            showCheckSpeed =
                com.roadpulse.auto.driving.SpeedComplianceAdvisor
                    .shouldShowCheckSpeed(compliance.level),
        )
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        carNavigationManager.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    stopNavigation()
                }
            },
        )
        TerrainGuidance.addListener(terrainListener)
        SpeedLimitAheadGuidance.addListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.addListener(routeRoadFeatureListener)
        RouteCameraGuidance.addListener(routeCameraListener)
        guidanceEngine.addListener(guidanceListener)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    TerrainGuidance.removeListener(terrainListener)
                    SpeedLimitAheadGuidance.removeListener(speedLimitAheadListener)
                    RouteRoadFeatureGuidance.removeListener(routeRoadFeatureListener)
                    RouteCameraGuidance.removeListener(routeCameraListener)
                    guidanceEngine.removeListener(guidanceListener)
                    carNavigationManager.clearNavigationManagerCallback()
                    locationManager?.removeUpdates(locationListener)
                    guidanceEngine.stopGuidance()
                    voiceGuidance.shutdown()
                }
            },
        )
        SelectedDestinationStore(carContext).load()?.let { selected ->
            statusTitle = "Destination ready: ${selected.title}"
            statusDetail = "Press Start for route guidance."
        }
        statusDetail += trafficSummary()
    }

    override fun onGetTemplate(): Template {
        val selected = SelectedDestinationStore(carContext).load()
        val routeAction =
            if (guidanceRunning) {
                Action
                    .Builder()
                    .setTitle("Stop")
                    .setIcon(routeActionIcon())
                    .setOnClickListener { stopNavigation() }
                    .build()
            } else {
                Action
                    .Builder()
                    .setTitle("Start")
                    .setIcon(routeActionIcon())
                    .setOnClickListener { startNavigation() }
                    .build()
            }
        val actionStrip =
            ActionStrip
                .Builder()
                .addAction(Action.APP_ICON)
                .apply { if (selected != null) addAction(routeAction) }
                .addAction(routeStopAction(supermarket = true))
                .addAction(routeStopAction(supermarket = false))
                .build()

        val navigationInfo =
            routingInfo.takeIf { guidanceRunning }
                ?: MessageInfo
                    .Builder(statusTitle)
                    .setText(statusDetail)
                    .build()

        return NavigationTemplate
            .Builder()
            .setNavigationInfo(navigationInfo)
            .setActionStrip(actionStrip)
            .setMapActionStrip(
                ActionStrip
                    .Builder()
                    .addAction(Action.PAN)
                    .build(),
            ).build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!surfaceContainer.isReady()) return
        destroyNavigationSurface()

        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        virtualDisplay =
            displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                surfaceContainer.width,
                surfaceContainer.height,
                surfaceContainer.dpi,
                surfaceContainer.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        val display = virtualDisplay?.display ?: return
        val newPresentation = Presentation(carContext, display)
        MapLibre.getInstance(carContext)
        val newMapView = MapView(carContext)
        newMapView.onCreate(null)
        newMapView.onStart()
        newMapView.onResume()
        val newSpeedComplianceRing =
            com.roadpulse.auto.driving.SpeedComplianceRingView(carContext).apply {
                layoutParams =
                    android.widget.FrameLayout
                        .LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.BOTTOM or android.view.Gravity.END,
                        ).apply {
                            val density = carContext.resources.displayMetrics.density
                            marginEnd = (16 * density).toInt()
                            bottomMargin = (16 * density).toInt()
                            width = (200 * density).toInt()
                        }
            }
        val content =
            android.widget.FrameLayout(carContext).apply {
                addView(newMapView)
                addView(newSpeedComplianceRing)
            }
        newPresentation.setContentView(content)
        newPresentation.show()

        presentation = newPresentation
        mapView = newMapView
        speedComplianceRing = newSpeedComplianceRing

        CompletableFuture
            .supplyAsync { startTileServerForBestRegion() }
            .thenAccept { server ->
                tileServer = server
                Handler(Looper.getMainLooper()).post { server?.let { loadMap(newMapView, it.port) } }
            }
    }

    /** Same "pick the region covering the last-known location, falling back to the first
     * installed region" logic as `MainActivity`/`NavigationActivity`. Runs off the main thread. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startTileServerForBestRegion(): LocalMbtilesServer? {
        val coordinate =
            if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val manager = carContext.getSystemService(LocationManager::class.java)
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
        return LocalMbtilesServer(region.tilesFile).apply { start() }
    }

    /** Same region-boundary handling as `MainActivity.switchActiveRegionIfNeeded` - see its doc
     * comment for why a brief reload on crossing a region boundary is an accepted v1 trade-off. */
    private fun switchActiveRegionIfNeeded(controller: MapLibreMapController) {
        val view = mapView ?: return
        val center = controller.cameraTarget() ?: return
        val active = activeRegionId?.let(regionInstallStore::region)
        if (active != null && active.bounds.contains(center)) return
        val next = regionInstallStore.regionContaining(center) ?: return
        if (next.id == active?.id) return
        tileServer?.stop()
        activeRegionId = next.id
        CompletableFuture
            .supplyAsync { LocalMbtilesServer(next.tilesFile).apply { start() } }
            .thenAccept { server ->
                tileServer = server
                Handler(Looper.getMainLooper()).post { loadMap(view, server.port) }
            }
    }

    private fun loadMap(
        view: MapView,
        port: Int,
    ) {
        if (mapView !== view) return
        val styleJson = RoadPulseMapLibreStyle.styleJson(carContext, port)
        view.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                val controller = MapLibreMapController(view, map, style)
                mapController = controller
                activeRoute?.let(::drawRoutePolyline)
                showSavedTrafficOverlay(controller)
                showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                controller.setOnCameraIdleListener {
                    switchActiveRegionIfNeeded(controller)
                    refreshTrafficForVisibleMap(controller)
                    showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                    showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
                }
                startLocationUpdates()
                if (guidanceRunning) {
                    markCarNavigationStarted()
                }
                refreshTrafficForVisibleMap(controller)
            }
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        destroyNavigationSurface()
    }

    override fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) {
        mapController?.scrollBy(distanceX, distanceY)
    }

    override fun onScale(
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
    ) {
        mapController?.zoomBy((scaleFactor - 1f).toDouble(), focusX.toInt(), focusY.toInt())
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (
            carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = carContext.getSystemService(LocationManager::class.java)
        locationManager = manager
        val provider =
            when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return
        manager.getLastKnownLocation(provider)?.let(::onLocationUpdate)
        manager.requestLocationUpdates(provider, LOCATION_UPDATE_INTERVAL_MILLIS, LOCATION_UPDATE_MIN_METERS, locationListener)
    }

    private fun onLocationUpdate(location: Location) {
        val coordinate = RoadCoordinate(location.latitude, location.longitude)
        guidanceEngine.onLocationUpdate(
            coordinate,
            speedKph = if (location.hasSpeed()) location.speed * 3.6f else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
        )
        val controller = mapController ?: return
        controller.registerIcon(MY_LOCATION_ICON_ID, myLocationPuckBitmap())
        controller.setMyLocationPuck(coordinate, location.bearing, MY_LOCATION_ICON_ID)
        if (guidanceRunning) controller.animateCameraTo(coordinate, DRIVING_ZOOM)
    }

    private fun myLocationPuckBitmap(): android.graphics.Bitmap {
        val size = (24 * carContext.resources.displayMetrics.density).toInt()
        val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(33, 150, 243)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (4 * carContext.resources.displayMetrics.density), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * carContext.resources.displayMetrics.density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (4 * carContext.resources.displayMetrics.density), paint)
        return bitmap
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startNavigation() {
        val selected =
            SelectedDestinationStore(carContext).load() ?: run {
                updateStatus("No destination", "Choose a destination on the phone first.")
                return
            }
        val destinationLatitude = selected.latitude
        val destinationLongitude = selected.longitude
        if (destinationLatitude == null || destinationLongitude == null) {
            updateStatus("Route unavailable", "This place cannot be used for driving guidance.")
            return
        }
        if (
            carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("Phone action needed", "Grant My Maps location permission on the phone.")
            return
        }
        val manager = locationManager ?: carContext.getSystemService(LocationManager::class.java).also { locationManager = it }
        val lastLocation =
            sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(Location::getTime)
        if (lastLocation == null) {
            updateStatus("Waiting for GPS", "Waiting for a location fix before starting guidance.")
            return
        }
        requestRoute(
            RoadCoordinate(lastLocation.latitude, lastLocation.longitude),
            RoadCoordinate(destinationLatitude, destinationLongitude),
            selected.title,
        )
    }

    private fun requestRoute(
        origin: RoadCoordinate,
        destination: RoadCoordinate,
        destinationTitle: String,
    ) {
        updateStatus("Preparing route", "Calculating a route to $destinationTitle…")
        val avoidHighways = RouteStylePreferencesStore(carContext).load() == RouteStyle.SCENIC
        routeStopOptimizer.setRoute(
            routingEngine = routingEngine,
            origin = origin,
            destination = destination,
            avoidHighways = avoidHighways,
            onProgress = { detail -> updateStatus("Optimizing route", detail) },
        ) { plan ->
            Handler(Looper.getMainLooper()).post {
                val route = plan.route
                if (route != null) {
                    activeRoute = route
                    guidanceRunning = true
                    guidanceEngine.startGuidance(route)
                    drawRoutePolyline(route)
                    showDestinationMarker(destination)
                    clearRouteIntelligence()
                    activeRoute?.let { refreshRouteIntelligence(it, force = true) }
                    markCarNavigationStarted()
                    updateStatus(
                        "Navigating to $destinationTitle",
                        plan.summary() ?: "Route guidance is active.",
                    )
                } else {
                    updateStatus(
                        "Route unavailable",
                        plan.summary() ?: plan.status.name
                            .replace('_', ' ')
                            .lowercase(),
                    )
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
        val size = (36 * carContext.resources.displayMetrics.density).toInt()
        val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(30, 136, 229)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (3 * carContext.resources.displayMetrics.density), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 3 * carContext.resources.displayMetrics.density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (3 * carContext.resources.displayMetrics.density), paint)
        return bitmap
    }

    private fun stopNavigation() {
        guidanceRunning = false
        guidanceEngine.stopGuidance()
        if (carNavigationStarted) {
            carNavigationManager.navigationEnded()
            carNavigationStarted = false
        }
        routingInfo = null
        clearRouteIntelligence()
        val selected = SelectedDestinationStore(carContext).load()
        updateStatus(
            selected?.let { "Destination ready: ${it.title}" } ?: "My Maps ready",
            "Guidance stopped. Press Start when you are ready.",
        )
    }

    private fun rebuildRoutingInfo(state: GuidanceState) {
        val roadAhead =
            DrivingAttention.build(
                speedLimit = latestSpeedLimit,
                roadFeatures = latestRoadFeatures,
                cameras = latestCameraSnapshot,
                terrain = latestTerrain,
                maneuverDistanceMeters = state.distanceToNextManeuverMeters,
            )
        val signboardGuidance =
            com.roadpulse.auto.signage.SignboardGuidanceEngine.build(
                state,
                latestRoadFeatures,
                RouteRoadFeatureGuidance.latestLaneTopologySections,
            )
        val junctionImage =
            com.roadpulse.auto.signage.SignboardRenderer
                .render(carContext, signboardGuidance, JUNCTION_IMAGE_WIDTH_PX, JUNCTION_IMAGE_HEIGHT_PX)
                ?.let { bitmap -> CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build() }
        routingInfo =
            AndroidAutoRoutingInfoFactory.create(
                state,
                roadAhead.primaryText.takeIf { roadAhead.primary != null },
                roadAhead.secondaryText,
                junctionImage,
            )
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
    }

    private fun refreshRouteIntelligence(
        route: Route,
        force: Boolean,
    ) {
        TerrainGuidance.refresh(carContext, route, force)
        SpeedLimitAheadGuidance.refresh(carContext, route, force)
        RouteRoadFeatureGuidance.refresh(carContext, route, force)
        RouteCameraGuidance.refresh(carContext, route, force)
    }

    private fun updateStatus(
        title: String,
        detail: String,
    ) {
        statusTitle = title
        statusDetail = detail + trafficSummary()
        invalidate()
    }

    private fun markCarNavigationStarted() {
        if (carNavigationStarted) return
        carNavigationManager.navigationStarted()
        carNavigationStarted = true
    }

    private fun trafficSummary(): String {
        val snapshot = TrafficSnapshotStore(carContext).load()
        val intelligence =
            roadIntelligenceSummary
                .takeIf(String::isNotBlank)
                ?.let { "$TRAFFIC_SEPARATOR$it" }
                .orEmpty()
        if (snapshot.timestampMillis <= 0L) return intelligence
        val age = System.currentTimeMillis() - snapshot.timestampMillis
        val freshness =
            when {
                age <= 5 * 60_000L -> "live"
                age <= 60 * 60_000L -> "saved"
                else -> "older saved"
            }
        return if (snapshot.events.isEmpty()) {
            "$TRAFFIC_SEPARATOR$freshness road scan: no events in the visible road area.$intelligence"
        } else {
            val worst = snapshot.events.maxByOrNull { it.delayMinutes ?: 0 }
            val delay = worst?.delayMinutes?.let { ", up to +$it min" }.orEmpty()
            "$TRAFFIC_SEPARATOR${snapshot.events.size} $freshness road event(s)$delay nearby.$intelligence"
        }
    }

    private fun refreshTrafficForVisibleMap(controller: MapLibreMapController) {
        if (trafficRefreshInProgress || controller.currentZoom() < MIN_TRAFFIC_QUERY_ZOOM) return
        val bounds = controller.visibleBounds()
        val centre = controller.cameraTarget() ?: return
        val showRoadSigns = displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.ROAD_SIGNS)
        val showAutobahnTraffic = displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.AUTOBAHN_TRAFFIC)
        val showAutobahnFacilities =
            displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.AUTOBAHN_FACILITIES)
        val showWeather = displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.WEATHER)
        trafficRefreshInProgress = true
        Thread {
            // Infrastructure/weather/warnings are independent and run concurrently; traffic and
            // facilities depend on infrastructure's autobahnRefs so they start once it resolves.
            val refreshed =
                runCatching {
                    val infrastructureFuture =
                        CompletableFuture.supplyAsync {
                            if (!showRoadSigns) {
                                return@supplyAsync com.roadpulse.auto.traffic.RoadInfrastructureResult(
                                    emptyList(),
                                    emptySet(),
                                    0L,
                                    false,
                                )
                            }
                            runCatching {
                                roadInfrastructureRepository.infrastructureInBounds(
                                    southLatitude = bounds.southWest.latitude,
                                    westLongitude = bounds.southWest.longitude,
                                    northLatitude = bounds.northEast.latitude,
                                    eastLongitude = bounds.northEast.longitude,
                                )
                            }.getOrElse {
                                com.roadpulse.auto.traffic
                                    .RoadInfrastructureResult(emptyList(), emptySet(), 0L, true)
                            }
                        }
                    val weatherFuture =
                        CompletableFuture.supplyAsync {
                            if (!showWeather) return@supplyAsync null
                            runCatching {
                                dwdRoadWeatherRepository.roadForecastNear(centre.latitude, centre.longitude)
                            }.getOrNull()
                        }
                    val warningsFuture =
                        CompletableFuture.supplyAsync {
                            if (!showWeather) return@supplyAsync emptyList<WeatherWarning>()
                            runCatching {
                                dwdRoadWeatherRepository
                                    .warningsInBounds(
                                        south = bounds.southWest.latitude,
                                        west = bounds.southWest.longitude,
                                        north = bounds.northEast.latitude,
                                        east = bounds.northEast.longitude,
                                    ).warnings
                                    .take(MAX_CAR_WARNINGS)
                            }.getOrDefault(emptyList())
                        }

                    val infrastructure = infrastructureFuture.get()
                    val trafficFuture =
                        CompletableFuture.supplyAsync {
                            if (!showAutobahnTraffic) return@supplyAsync TrafficEventResult(emptyList(), 0L, false, 0)
                            runCatching {
                                autobahnTrafficRepository.eventsForRoads(infrastructure.autobahnRefs)
                            }.getOrElse {
                                TrafficEventResult(emptyList(), 0L, true, infrastructure.autobahnRefs.size)
                            }.let { result ->
                                result.copy(
                                    events =
                                        result.events.filter { event ->
                                            event.geometry.any { coordinate -> bounds.contains(coordinate) }
                                        },
                                )
                            }
                        }
                    val facilitiesFuture =
                        CompletableFuture.supplyAsync {
                            if (!showAutobahnFacilities) {
                                return@supplyAsync com.roadpulse.auto.traffic
                                    .RoadFacilityResult(emptyList(), 0L, false)
                            }
                            runCatching {
                                autobahnFacilityRepository.facilitiesForRoads(infrastructure.autobahnRefs)
                            }.getOrElse {
                                com.roadpulse.auto.traffic
                                    .RoadFacilityResult(emptyList(), 0L, true)
                            }
                        }
                    val openChargeMapFuture =
                        CompletableFuture.supplyAsync {
                            if (!showAutobahnFacilities) return@supplyAsync emptyList()
                            runCatching {
                                openChargeMapRepository.chargersInBounds(
                                    southLatitude = bounds.southWest.latitude,
                                    westLongitude = bounds.southWest.longitude,
                                    northLatitude = bounds.northEast.latitude,
                                    eastLongitude = bounds.northEast.longitude,
                                )
                            }.getOrDefault(emptyList())
                        }

                    val traffic = trafficFuture.get()
                    val autobahnFacilities = facilitiesFuture.get()
                    val openChargeMapFacilities = openChargeMapFuture.get()
                    val facilities =
                        (
                            autobahnFacilities.facilities + infrastructure.facilities + openChargeMapFacilities
                        ).distinctBy(RoadFacility::id)
                            .filter { bounds.contains(it.coordinate) }
                            .take(MAX_CAR_FACILITIES)
                    val weather = weatherFuture.get()
                    val warnings = warningsFuture.get()
                    CarRoadContext(
                        traffic = traffic,
                        facilities = facilities,
                        infrastructure =
                            infrastructure.points
                                .filter { it.shouldDisplayOnMap && !it.isRouteGuidanceFeature }
                                .filter { roadSignFilters.isEnabled(DrivingContext.DRIVING, it.type) }
                                .sortedBy(::carSafetyPriority)
                                .take(MAX_CAR_INFRASTRUCTURE),
                        speedLimitSections = infrastructure.speedLimitSections,
                        weather = weather,
                        warnings = warnings,
                    )
                }
            Handler(Looper.getMainLooper()).post {
                trafficRefreshInProgress = false
                if (mapController !== controller) return@post
                refreshed.onSuccess { result ->
                    TrafficSnapshotStore(carContext).save(
                        result.traffic.events,
                        result.traffic.timestampMillis,
                        result.traffic.usedSavedData,
                    )
                    showSavedTrafficOverlay(controller)
                    showRoadIntelligenceOverlay(controller, result)
                    roadIntelligenceSummary = result.summary()
                    statusDetail = statusDetail.substringBefore(TRAFFIC_SEPARATOR) + trafficSummary()
                    invalidate()
                }
            }
        }.start()
    }

    private fun showSavedTrafficOverlay(controller: MapLibreMapController) {
        trafficMarkers.forEach(controller::removeMarker)
        trafficMarkers.clear()
        trafficLines.forEach(controller::removePolyline)
        trafficLines.clear()
        TrafficSnapshotStore(carContext).load().events.forEach { event ->
            val start = event.start ?: return@forEach
            val end = event.end ?: start
            val (startIconId, startBitmap) = mapMarkerIcons.trafficEvent(event.type, "S")
            controller.registerIcon(startIconId, startBitmap)
            trafficMarkers += controller.addMarker(start, startIconId)
            if (start != end) {
                val (endIconId, endBitmap) = mapMarkerIcons.trafficEvent(event.type, "E")
                controller.registerIcon(endIconId, endBitmap)
                trafficMarkers += controller.addMarker(end, endIconId)
                trafficLines += controller.addPolyline(listOf(start, end), trafficLineColorHex(event.type), widthDp = 7f)
            }
        }
    }

    private fun showRoadIntelligenceOverlay(
        controller: MapLibreMapController,
        context: CarRoadContext,
    ) {
        context.speedLimitSections.take(MAX_CAR_SPEED_LIMIT_ROADS).forEach { section ->
            if (section.geometry.size < 2) return@forEach
            trafficLines += controller.addPolyline(section.geometry, SpeedLimitRoadStyle.colour(section).toHexColor(), widthDp = 7f)
        }
        context.infrastructure.forEach { point ->
            val (iconId, bitmap) = mapMarkerIcons.infrastructure(point)
            controller.registerIcon(iconId, bitmap)
            trafficMarkers += controller.addMarker(point.coordinate, iconId)
        }
        context.facilities.forEach { facility ->
            val (iconId, bitmap) = mapMarkerIcons.facility(facility)
            controller.registerIcon(iconId, bitmap)
            trafficMarkers += controller.addMarker(facility.coordinate, iconId)
        }
        context.warnings.forEach { warning ->
            val (iconId, bitmap) = mapMarkerIcons.weatherWarning()
            controller.registerIcon(iconId, bitmap)
            trafficMarkers += controller.addMarker(warning.coordinate, iconId)
        }
        context.weather?.mostSevere?.let { forecast ->
            val (iconId, bitmap) = mapMarkerIcons.roadWeather(forecast.condition)
            controller.registerIcon(iconId, bitmap)
            trafficMarkers += controller.addMarker(forecast.coordinate, iconId)
        }
    }

    private fun Int.toHexColor(): String = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and this)

    private fun showRouteRoadFeatureMarkers(upcoming: List<UpcomingRouteRoadFeature>) {
        val controller = mapController ?: return
        val visible = if (guidanceRunning) routeFeaturesInsideVisibleMap(controller, upcoming) else emptyList()
        val pointIds = visible.map { it.point.id }
        if (pointIds == routeRoadFeatureMarkerIds) return
        routeRoadFeatureMarkers.forEach(controller::removeMarker)
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = pointIds
        if (!guidanceRunning) return
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
            if (!guidanceRunning) {
                emptyList()
            } else if (bounds == null) {
                upcoming
            } else {
                upcoming.filter { camera ->
                    bounds.contains(RoadCoordinate(camera.camera.poi.latitude, camera.camera.poi.longitude))
                }
            }
        val ids = visible.map(UpcomingRouteCamera::id)
        if (ids == routeCameraMarkerIds) return
        routeCameraMarkers.forEach(controller::removeMarker)
        routeCameraMarkers.clear()
        routeCameraMarkerIds = ids
        if (!guidanceRunning) return
        visible.forEach { upcomingCamera ->
            val camera = upcomingCamera.camera.poi
            val (iconId, bitmap) = mapMarkerIcons.camera(camera.type, camera.speedLimitKph)
            controller.registerIcon(iconId, bitmap)
            routeCameraMarkers += controller.addMarker(RoadCoordinate(camera.latitude, camera.longitude), iconId)
        }
    }

    private fun carSafetyPriority(point: RoadInfrastructurePoint): Int =
        when (point.type) {
            RoadInfrastructureType.STOP_SIGN,
            RoadInfrastructureType.GIVE_WAY_SIGN,
            RoadInfrastructureType.PRIORITY_ROAD_SIGN,
            RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN,
            RoadInfrastructureType.SPEED_LIMIT_SIGN,
            RoadInfrastructureType.ROAD_RULE_START,
            RoadInfrastructureType.ROAD_RULE_END,
            RoadInfrastructureType.TRAFFIC_RESTRICTION,
            RoadInfrastructureType.DIMENSION_RESTRICTION,
            -> 0
            RoadInfrastructureType.TRAFFIC_SIGNAL,
            RoadInfrastructureType.RAILWAY_CROSSING,
            RoadInfrastructureType.SCHOOL_ZONE,
            RoadInfrastructureType.TOLL,
            -> 1
            RoadInfrastructureType.TUNNEL,
            RoadInfrastructureType.BRIDGE,
            RoadInfrastructureType.STEEP_GRADE,
            RoadInfrastructureType.SURFACE_HAZARD,
            RoadInfrastructureType.MOTORWAY_JUNCTION,
            -> 2
            RoadInfrastructureType.TRAFFIC_CALMING -> 3
            RoadInfrastructureType.PEDESTRIAN_CROSSING -> 4
            RoadInfrastructureType.OTHER_SIGN -> 5
        }

    private fun trafficLineColorHex(type: TrafficEventType): String =
        when (type) {
            TrafficEventType.QUEUE -> "#E53935"
            TrafficEventType.WARNING -> "#FB8C00"
            TrafficEventType.ROADWORK -> "#F57C00"
            TrafficEventType.CLOSURE -> "#7B1FA2"
        }

    private fun routeActionIcon(): CarIcon =
        CarIcon
            .Builder(
                IconCompat.createWithResource(carContext, R.drawable.ic_route_action),
            ).build()

    private fun routeStopAction(supermarket: Boolean): Action {
        val mode =
            routeStopPreferences.load().let {
                if (supermarket) it.supermarketMode else it.fuelMode
            }
        val label = if (supermarket) "Market" else "Fuel"
        val title = "$label ${mode.shortLabel}"
        val icon = if (supermarket) R.drawable.ic_supermarket_action else R.drawable.ic_fuel_action
        return Action
            .Builder()
            .setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { toggleRouteStop(supermarket) }
            .build()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun toggleRouteStop(supermarket: Boolean) {
        val current = routeStopPreferences.load()
        val mode = if (supermarket) current.supermarketMode.next() else current.fuelMode.next()
        if (supermarket) {
            routeStopPreferences.setSupermarketMode(mode)
        } else {
            routeStopPreferences.setFuelMode(mode)
        }
        val label = if (supermarket) "Supermarket" else "Fuel"
        val selected = SelectedDestinationStore(carContext).load()
        if (guidanceRunning && selected?.latitude != null && selected.longitude != null) {
            updateStatus("$label ${mode.shortLabel}", "Re-optimizing the remaining route…")
            val manager = locationManager
            val lastLocation =
                manager?.let {
                    runCatching {
                        sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                            .mapNotNull { provider -> it.getLastKnownLocation(provider) }
                            .maxByOrNull(Location::getTime)
                    }.getOrNull()
                }
            if (lastLocation != null) {
                requestRoute(
                    RoadCoordinate(lastLocation.latitude, lastLocation.longitude),
                    RoadCoordinate(selected.latitude, selected.longitude),
                    selected.title,
                )
            }
        } else {
            updateStatus(
                "$label stop ${mode.shortLabel}",
                "The setting will apply when navigation starts.",
            )
        }
        invalidate()
    }

    private fun destroyNavigationSurface() {
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        mapView = null
        tileServer?.stop()
        tileServer = null
        mapController = null
        speedComplianceRing = null
        trafficMarkers.clear()
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = emptyList()
        routeCameraMarkers.clear()
        routeCameraMarkerIds = emptyList()
        trafficLines.clear()
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun SurfaceContainer.isReady(): Boolean = surface != null && surface?.isValid == true && width > 0 && height > 0 && dpi > 0

    private data class CarRoadContext(
        val traffic: TrafficEventResult,
        val facilities: List<RoadFacility>,
        val infrastructure: List<RoadInfrastructurePoint>,
        val speedLimitSections: List<com.roadpulse.auto.traffic.SpeedLimitRoadSection>,
        val weather: RoadWeatherResult?,
        val warnings: List<WeatherWarning>,
    ) {
        fun summary(): String =
            buildList {
                val speedLimits =
                    infrastructure.count {
                        it.type == RoadInfrastructureType.SPEED_LIMIT_SIGN
                    }
                if (speedLimits > 0) add("$speedLimits mapped speed limit(s)")
                if (speedLimitSections.isNotEmpty()) {
                    add("${speedLimitSections.size} speed-coloured road section(s)")
                }
                val otherInfrastructure = infrastructure.size - speedLimits
                if (otherInfrastructure > 0) add("$otherInfrastructure safety point(s)")
                if (facilities.isNotEmpty()) add("${facilities.size} road service(s)")
                val restrooms = facilities.filter { it.type == RoadFacilityType.RESTROOM }
                if (restrooms.isNotEmpty()) {
                    val free =
                        restrooms.count {
                            it.restroomFeeStatus == com.roadpulse.auto.traffic.RestroomFeeStatus.FREE
                        }
                    val paid =
                        restrooms.count {
                            it.restroomFeeStatus == com.roadpulse.auto.traffic.RestroomFeeStatus.PAID
                        }
                    add("${restrooms.size} WC ($free free, $paid paid)")
                }
                if (warnings.isNotEmpty()) add("${warnings.size} weather warning(s)")
                weather?.mostSevere?.let { add("road surface ${it.condition.displayName.lowercase()}") }
            }.joinToString(" · ").let { summary ->
                if (summary.isBlank()) "No extra road advisories." else "$summary."
            }
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "My Maps Android Auto navigation"
        private const val MIN_TRAFFIC_QUERY_ZOOM = 10f
        private const val MAX_CAR_INFRASTRUCTURE = 18
        private const val MAX_CAR_FACILITIES = 20
        private const val MAX_CAR_WARNINGS = 8
        private const val MAX_CAR_SPEED_LIMIT_ROADS = 180
        private const val TRAFFIC_SEPARATOR = " • "
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val LOCATION_UPDATE_MIN_METERS = 3f
        private const val DRIVING_ZOOM = 17.0
        private const val DESTINATION_ICON_ID = "car-destination-pin"
        private const val MY_LOCATION_ICON_ID = "car-my-location-puck"
        private const val ROUTE_LINE_COLOR_HEX = "#4F7CFF"

        // androidx.car.app 1.7.0 does not expose a queryable max size for RoutingInfo's junction
        // image (unlike ConstraintManager's list/grid content limits) - the host scales/clips as
        // needed. This is a conservative default pending real head-unit/DHU size verification.
        private const val JUNCTION_IMAGE_WIDTH_PX = 480
        private const val JUNCTION_IMAGE_HEIGHT_PX = 180
    }
}
