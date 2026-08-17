package com.roadpulse.auto.car

import android.Manifest
import android.app.Application
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.CameraPerspective
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationViewForAuto
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
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
import com.roadpulse.auto.map.MapMarkerIconFactory
import com.roadpulse.auto.map.RoadPulseMapTheme
import com.roadpulse.auto.map.SpeedLimitRoadStyle
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
import com.roadpulse.auto.traffic.TrafficEvent
import com.roadpulse.auto.traffic.TrafficEventResult
import com.roadpulse.auto.traffic.TrafficEventType
import com.roadpulse.auto.traffic.TrafficSnapshotStore
import com.roadpulse.auto.traffic.WeatherWarning
import com.roadpulse.auto.traffic.displayName
import java.util.concurrent.CompletableFuture

/**
 * Android Auto surface backed by Google's NavigationViewForAuto.
 *
 * Real German enforcement points are deliberately not loaded in this class. The projected
 * experience contains Google's route map plus navigation and generic road-safety information.
 */
class RoadPulseNavigationScreen(
    carContext: CarContext,
) : Screen(carContext),
    SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var navigationView: NavigationViewForAuto? = null
    private var speedComplianceRing: com.roadpulse.auto.driving.SpeedComplianceRingView? = null
    private var googleMap: GoogleMap? = null
    private var navigator: Navigator? = null
    private val routeStopPreferences = RouteStopPreferencesStore(carContext)
    private val routeStopOptimizer = RouteStopOptimizer(carContext)
    private val displayFilters = DisplayFilterStore(carContext)
    private val roadSignFilters = RoadSignFilterStore(carContext)
    private val arrivalListener =
        Navigator.ArrivalListener { event ->
            if (!event.isFinalDestination) {
                navigator?.continueToNextDestination()
                updateStatus(
                    "Stop reached: ${event.waypoint.title}",
                    "Continuing to the next destination.",
                )
            }
        }
    private val carNavigationManager = carContext.getCarService(NavigationManager::class.java)
    private var carNavigationStarted = false
    private val trafficMarkers = mutableListOf<Marker>()
    private val routeRoadFeatureMarkers = mutableListOf<Marker>()
    private var routeRoadFeatureMarkerIds: List<String> = emptyList()
    private val routeCameraMarkers = mutableListOf<Marker>()
    private var routeCameraMarkerIds: List<String> = emptyList()
    private val trafficLines = mutableListOf<Polyline>()
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
    private val turnByTurnListener: (com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo) -> Unit =
        { navInfo ->
            rebuildRoutingInfo(navInfo)
            navigator?.let {
                if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.TERRAIN)) {
                    TerrainGuidance.refresh(carContext, it)
                }
                if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_LIMIT_AHEAD)) {
                    SpeedLimitAheadGuidance.refresh(carContext, it)
                }
                if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.ROAD_SIGNS)) {
                    RouteRoadFeatureGuidance.refresh(carContext, it)
                }
                if (displayFilters.isEnabled(DrivingContext.DRIVING, DisplayLayer.SPEED_CAMERAS)) {
                    RouteCameraGuidance.refresh(carContext, it)
                }
            }
            when (navInfo.navState) {
                com.google.android.libraries.mapsplatform.turnbyturn.model.NavState.REROUTING -> {
                    statusTitle = "Finding a new route"
                    statusDetail = "Lane guidance will return with the new route."
                }
                com.google.android.libraries.mapsplatform.turnbyturn.model.NavState.STOPPED -> {
                    routingInfo = null
                }
            }
            invalidate()
        }
    private val terrainListener: (ElevationProfileSummary?) -> Unit = { summary ->
        latestTerrain = summary
        TurnByTurnState.latest?.let(::rebuildRoutingInfo)
        invalidate()
    }
    private val speedLimitAheadListener: (SpeedLimitAheadSummary?) -> Unit = { summary ->
        latestSpeedLimit = summary
        TurnByTurnState.latest?.let(::rebuildRoutingInfo)
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
        TurnByTurnState.latest?.let(::rebuildRoutingInfo)
        invalidate()
    }
    private val routeCameraListener: (RouteCameraSnapshot) -> Unit = { snapshot ->
        latestCameraSnapshot = snapshot
        showRouteCameraMarkers(snapshot.cameras)
        TurnByTurnState.latest?.let(::rebuildRoutingInfo)
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

    private val routeChangedListener =
        Navigator.RouteChangedListener {
            navigator?.let { refreshRouteIntelligence(it, force = true) }
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
        TurnByTurnState.addListener(turnByTurnListener)
        TerrainGuidance.addListener(terrainListener)
        SpeedLimitAheadGuidance.addListener(speedLimitAheadListener)
        RouteRoadFeatureGuidance.addListener(routeRoadFeatureListener)
        RouteCameraGuidance.addListener(routeCameraListener)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    TurnByTurnState.removeListener(turnByTurnListener)
                    TerrainGuidance.removeListener(terrainListener)
                    SpeedLimitAheadGuidance.removeListener(speedLimitAheadListener)
                    RouteRoadFeatureGuidance.removeListener(routeRoadFeatureListener)
                    RouteCameraGuidance.removeListener(routeCameraListener)
                    carNavigationManager.clearNavigationManagerCallback()
                    navigator?.removeArrivalListener(arrivalListener)
                    navigator?.removeRouteChangedListener(routeChangedListener)
                }
            },
        )
        SelectedDestinationStore(carContext).load()?.let { selected ->
            statusTitle = "Destination ready: ${selected.title}"
            statusDetail = "Press Start for Google route guidance."
        }
        statusDetail += trafficSummary()
    }

    override fun onGetTemplate(): Template {
        val selected = SelectedDestinationStore(carContext).load()
        val routeAction =
            if (navigator?.isGuidanceRunning == true) {
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
            routingInfo.takeIf { navigator?.isGuidanceRunning == true }
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
        val newNavigationView = NavigationViewForAuto(carContext)
        newNavigationView.onCreate(null)
        newNavigationView.onStart()
        newNavigationView.onResume()
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
                addView(newNavigationView)
                addView(newSpeedComplianceRing)
            }
        newPresentation.setContentView(content)
        newPresentation.show()

        presentation = newPresentation
        navigationView = newNavigationView
        speedComplianceRing = newSpeedComplianceRing
        newNavigationView.getMapAsync { readyMap ->
            googleMap = readyMap
            RoadPulseMapTheme.apply(carContext, readyMap)
            showSavedTrafficOverlay(readyMap)
            showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
            showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
            readyMap.setOnCameraIdleListener {
                refreshTrafficForVisibleMap(readyMap)
                showRouteRoadFeatureMarkers(RouteRoadFeatureGuidance.latest)
                showRouteCameraMarkers(RouteCameraGuidance.latest.cameras)
            }
            initializeNavigatorForMap()
            refreshTrafficForVisibleMap(readyMap)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        destroyNavigationSurface()
    }

    override fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) {
        googleMap?.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onScale(
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
    ) {
        googleMap?.animateCamera(
            CameraUpdateFactory.zoomBy(scaleFactor - 1f, android.graphics.Point(focusX.toInt(), focusY.toInt())),
        )
    }

    private fun startNavigation() {
        val selected =
            SelectedDestinationStore(carContext).load() ?: run {
                updateStatus("No destination", "Choose a destination on the phone first.")
                return
            }
        if (
            carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("Phone action needed", "Grant My Maps location permission on the phone.")
            return
        }
        updateStatus("Preparing route", "Calculating a Google route to ${selected.title}…")
        NavigationApi.getNavigator(
            carContext.applicationContext as Application,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    readyNavigator.removeArrivalListener(arrivalListener)
                    readyNavigator.addArrivalListener(arrivalListener)
                    readyNavigator.removeRouteChangedListener(routeChangedListener)
                    readyNavigator.addRouteChangedListener(routeChangedListener)
                    LaneGuidance.register(carContext, readyNavigator)
                    requestRoute(readyNavigator)
                }

                override fun onError(errorCode: Int) {
                    val detail =
                        if (errorCode == NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED) {
                            "Open My Maps on the phone, press Start, and accept Google's terms once."
                        } else {
                            "Google Navigation could not start (error $errorCode)."
                        }
                    updateStatus("Phone action needed", detail)
                }
            },
        )
    }

    private fun initializeNavigatorForMap() {
        NavigationApi.getNavigator(
            carContext.applicationContext as Application,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    navigator = readyNavigator
                    readyNavigator.removeRouteChangedListener(routeChangedListener)
                    readyNavigator.addRouteChangedListener(routeChangedListener)
                    LaneGuidance.register(carContext, readyNavigator)
                    if (
                        carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        googleMap?.followMyLocation(CameraPerspective.TILTED)
                    }
                    if (readyNavigator.isGuidanceRunning) {
                        markCarNavigationStarted()
                        refreshRouteIntelligence(readyNavigator, force = true)
                        val selected = SelectedDestinationStore(carContext).load()
                        updateStatus(
                            selected?.let { "Navigating to ${it.title}" } ?: "Navigation active",
                            "Google route guidance is active.",
                        )
                    }
                }

                override fun onError(errorCode: Int) {
                    if (errorCode == NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED) {
                        updateStatus(
                            statusTitle,
                            "Accept Google's navigation terms once on the phone, then press Start.",
                        )
                    }
                }
            },
        )
    }

    private fun requestRoute(readyNavigator: Navigator) {
        val selected = SelectedDestinationStore(carContext).load() ?: return
        val waypoint =
            runCatching {
                Waypoint
                    .builder()
                    .setPlaceIdString(selected.placeId)
                    .setTitle(selected.title)
                    .build()
            }.getOrElse {
                updateStatus("Route unavailable", "This place cannot be used for driving guidance.")
                return
            }
        when (GoogleUsageGuard(carContext).navigationDestinations.tryConsume()) {
            is QuotaDecision.Blocked -> {
                updateStatus("Monthly limit reached", "The 1,000-destination guard resets next month.")
                return
            }
            is QuotaDecision.Allowed -> Unit
        }
        val options =
            RoutingOptions().apply {
                travelMode(RoutingOptions.TravelMode.DRIVING)
                avoidHighways(RouteStylePreferencesStore(carContext).load() == RouteStyle.SCENIC)
            }
        routeStopOptimizer.setRoute(
            navigator = readyNavigator,
            finalDestination = waypoint,
            routingOptions = options,
            onProgress = { detail -> updateStatus("Optimizing route", detail) },
        ) { plan ->
            if (plan.status == Navigator.RouteStatus.OK) {
                readyNavigator.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                readyNavigator.startGuidance()
                clearRouteIntelligence()
                refreshRouteIntelligence(readyNavigator, force = true)
                markCarNavigationStarted()
                updateStatus(
                    "Navigating to ${selected.title}",
                    plan.summary() ?: "Google route guidance is active.",
                )
            } else {
                updateStatus(
                    "Route unavailable",
                    plan.status.name
                        .replace('_', ' ')
                        .lowercase(),
                )
            }
        }
    }

    private fun stopNavigation() {
        navigator?.stopGuidance()
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

    private fun rebuildRoutingInfo(navInfo: com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo) {
        val roadAhead =
            DrivingAttention.build(
                speedLimit = latestSpeedLimit,
                roadFeatures = latestRoadFeatures,
                cameras = latestCameraSnapshot,
                terrain = latestTerrain,
                maneuverDistanceMeters = navInfo.distanceToCurrentStepMeters,
            )
        val signboardGuidance =
            com.roadpulse.auto.signage.SignboardGuidanceEngine.build(
                navInfo,
                latestRoadFeatures,
                com.roadpulse.auto.driving.RouteRoadFeatureGuidance.latestLaneTopologySections,
            )
        val junctionImage =
            com.roadpulse.auto.signage.SignboardRenderer
                .render(carContext, signboardGuidance, JUNCTION_IMAGE_WIDTH_PX, JUNCTION_IMAGE_HEIGHT_PX)
                ?.let { bitmap -> CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build() }
        routingInfo =
            AndroidAutoRoutingInfoFactory.create(
                navInfo,
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
        readyNavigator: Navigator,
        force: Boolean,
    ) {
        TerrainGuidance.refresh(carContext, readyNavigator, force)
        SpeedLimitAheadGuidance.refresh(carContext, readyNavigator, force)
        RouteRoadFeatureGuidance.refresh(carContext, readyNavigator, force)
        RouteCameraGuidance.refresh(carContext, readyNavigator, force)
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

    private fun refreshTrafficForVisibleMap(map: GoogleMap) {
        if (trafficRefreshInProgress || map.cameraPosition.zoom < MIN_TRAFFIC_QUERY_ZOOM) return
        val bounds = map.projection.visibleRegion.latLngBounds
        val centre = map.cameraPosition.target
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
                                    southLatitude = bounds.southwest.latitude,
                                    westLongitude = bounds.southwest.longitude,
                                    northLatitude = bounds.northeast.latitude,
                                    eastLongitude = bounds.northeast.longitude,
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
                                        south = bounds.southwest.latitude,
                                        west = bounds.southwest.longitude,
                                        north = bounds.northeast.latitude,
                                        east = bounds.northeast.longitude,
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
                                            event.geometry.any { coordinate -> coordinateIsInside(coordinate, bounds) }
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
                                    southLatitude = bounds.southwest.latitude,
                                    westLongitude = bounds.southwest.longitude,
                                    northLatitude = bounds.northeast.latitude,
                                    eastLongitude = bounds.northeast.longitude,
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
                            .filter { coordinateIsInside(it.coordinate, bounds) }
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
                if (googleMap !== map) return@post
                refreshed.onSuccess { result ->
                    TrafficSnapshotStore(carContext).save(
                        result.traffic.events,
                        result.traffic.timestampMillis,
                        result.traffic.usedSavedData,
                    )
                    showSavedTrafficOverlay(map)
                    showRoadIntelligenceOverlay(map, result)
                    roadIntelligenceSummary = result.summary()
                    statusDetail = statusDetail.substringBefore(TRAFFIC_SEPARATOR) + trafficSummary()
                    invalidate()
                }
            }
        }.start()
    }

    private fun coordinateIsInside(
        coordinate: RoadCoordinate,
        bounds: LatLngBounds,
    ): Boolean =
        coordinate.latitude in bounds.southwest.latitude..bounds.northeast.latitude &&
            if (bounds.southwest.longitude <= bounds.northeast.longitude) {
                coordinate.longitude in bounds.southwest.longitude..bounds.northeast.longitude
            } else {
                coordinate.longitude >= bounds.southwest.longitude ||
                    coordinate.longitude <= bounds.northeast.longitude
            }

    private fun showSavedTrafficOverlay(map: GoogleMap) {
        trafficMarkers.forEach(Marker::remove)
        trafficMarkers.clear()
        trafficLines.forEach(Polyline::remove)
        trafficLines.clear()
        TrafficSnapshotStore(carContext).load().events.forEach { event ->
            val start = event.start ?: return@forEach
            val end = event.end ?: start
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(start.latitude, start.longitude))
                        .title("${event.type.displayName} starts")
                        .snippet(eventOverlayDetail(event))
                        .icon(mapMarkerIcons.trafficEvent(event.type, "S")),
                )?.let(trafficMarkers::add)
            if (start != end) {
                map
                    .addMarker(
                        MarkerOptions()
                            .position(LatLng(end.latitude, end.longitude))
                            .title("${event.type.displayName} ends")
                            .snippet(eventOverlayDetail(event))
                            .icon(mapMarkerIcons.trafficEvent(event.type, "E")),
                    )?.let(trafficMarkers::add)
                map
                    .addPolyline(
                        PolylineOptions()
                            .add(
                                LatLng(start.latitude, start.longitude),
                                LatLng(end.latitude, end.longitude),
                            ).color(trafficLineColour(event.type))
                            .width(7f),
                    ).let(trafficLines::add)
            }
        }
    }

    private fun showRoadIntelligenceOverlay(
        map: GoogleMap,
        context: CarRoadContext,
    ) {
        context.speedLimitSections.take(MAX_CAR_SPEED_LIMIT_ROADS).forEach { section ->
            if (section.geometry.size < 2) return@forEach
            map
                .addPolyline(
                    PolylineOptions()
                        .addAll(section.geometry.map { LatLng(it.latitude, it.longitude) })
                        .color(SpeedLimitRoadStyle.colour(section))
                        .width(7f)
                        .zIndex(.2f),
                ).let(trafficLines::add)
        }
        context.infrastructure.forEach { point ->
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                        .title(point.title)
                        .snippet(point.detail)
                        .icon(mapMarkerIcons.infrastructure(point))
                        .anchor(.5f, .5f),
                )?.let(trafficMarkers::add)
        }
        context.facilities.forEach { facility ->
            map
                .addMarker(
                    MarkerOptions()
                        .position(
                            LatLng(
                                facility.coordinate.latitude,
                                facility.coordinate.longitude,
                            ),
                        ).title(facility.title)
                        .snippet(facilityOverlayDetail(facility))
                        .icon(mapMarkerIcons.facility(facility))
                        .anchor(.5f, .5f),
                )?.let(trafficMarkers::add)
        }
        context.warnings.forEach { warning ->
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(warning.coordinate.latitude, warning.coordinate.longitude))
                        .title(warning.event)
                        .snippet(warning.headline.ifBlank { warning.severity })
                        .icon(mapMarkerIcons.weatherWarning())
                        .anchor(.5f, .5f),
                )?.let(trafficMarkers::add)
        }
        context.weather?.mostSevere?.let { forecast ->
            val temperature =
                forecast.surfaceTemperatureC
                    ?.let { " · surface %.1f°C".format(it) }
                    .orEmpty()
            map
                .addMarker(
                    MarkerOptions()
                        .position(
                            LatLng(
                                forecast.coordinate.latitude,
                                forecast.coordinate.longitude,
                            ),
                        ).title("Road surface: ${forecast.condition.displayName}")
                        .snippet("DWD forecast$temperature")
                        .icon(mapMarkerIcons.roadWeather(forecast.condition))
                        .anchor(.5f, .5f),
                )?.let(trafficMarkers::add)
        }
    }

    private fun showRouteRoadFeatureMarkers(upcoming: List<UpcomingRouteRoadFeature>) {
        val map = googleMap ?: return
        val guidanceRunning = navigator?.isGuidanceRunning == true
        val visible = if (guidanceRunning) routeFeaturesInsideVisibleMap(map, upcoming) else emptyList()
        val pointIds = visible.map { it.point.id }
        if (pointIds == routeRoadFeatureMarkerIds) return
        routeRoadFeatureMarkers.forEach(Marker::remove)
        routeRoadFeatureMarkers.clear()
        routeRoadFeatureMarkerIds = pointIds
        if (!guidanceRunning) return
        visible.forEach { sign ->
            val point = sign.point
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                        .title(point.title)
                        .snippet("On your route · ${sign.compactText()}")
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
        return upcoming.filter { sign -> coordinateIsInside(sign.point.coordinate, bounds) }
    }

    private fun showRouteCameraMarkers(upcoming: List<UpcomingRouteCamera>) {
        val map = googleMap ?: return
        val guidanceRunning = navigator?.isGuidanceRunning == true
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
        val visible =
            if (!guidanceRunning) {
                emptyList()
            } else if (bounds == null) {
                upcoming
            } else {
                upcoming.filter { camera ->
                    coordinateIsInside(
                        RoadCoordinate(camera.camera.poi.latitude, camera.camera.poi.longitude),
                        bounds,
                    )
                }
            }
        val ids = visible.map(UpcomingRouteCamera::id)
        if (ids == routeCameraMarkerIds) return
        routeCameraMarkers.forEach(Marker::remove)
        routeCameraMarkers.clear()
        routeCameraMarkerIds = ids
        if (!guidanceRunning) return
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

    private fun facilityOverlayDetail(facility: RoadFacility): String =
        buildString {
            append(facility.roadId)
            facility.maximumChargingPowerKw?.let { append(" · up to $it kW") }
            facility.lorrySpaces?.let { append(" · $it lorry spaces") }
            facility.carSpaces?.let { append(" · $it car spaces") }
            if (facility.subtitle.isNotBlank()) append(" · ${facility.subtitle}")
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

    private fun eventOverlayDetail(event: TrafficEvent): String =
        buildString {
            append(event.roadId)
            if (event.direction.isNotBlank()) append(" ${event.direction}")
            event.delayMinutes?.let { append(" · +$it min") }
        }

    private fun trafficLineColour(type: TrafficEventType): Int =
        when (type) {
            TrafficEventType.QUEUE -> android.graphics.Color.rgb(229, 57, 53)
            TrafficEventType.WARNING -> android.graphics.Color.rgb(251, 140, 0)
            TrafficEventType.ROADWORK -> android.graphics.Color.rgb(245, 124, 0)
            TrafficEventType.CLOSURE -> android.graphics.Color.rgb(123, 31, 162)
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

    private fun toggleRouteStop(supermarket: Boolean) {
        val current = routeStopPreferences.load()
        val mode = if (supermarket) current.supermarketMode.next() else current.fuelMode.next()
        if (supermarket) {
            routeStopPreferences.setSupermarketMode(mode)
        } else {
            routeStopPreferences.setFuelMode(mode)
        }
        val label = if (supermarket) "Supermarket" else "Fuel"
        if (navigator?.isGuidanceRunning == true) {
            updateStatus("$label ${mode.shortLabel}", "Re-optimizing the remaining route…")
            navigator?.let(::requestRoute)
        } else {
            updateStatus(
                "$label stop ${mode.shortLabel}",
                "The setting will apply when navigation starts.",
            )
        }
        invalidate()
    }

    private fun destroyNavigationSurface() {
        navigationView?.onPause()
        navigationView?.onStop()
        navigationView?.onDestroy()
        navigationView = null
        speedComplianceRing = null
        googleMap = null
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

        // androidx.car.app 1.7.0 does not expose a queryable max size for RoutingInfo's junction
        // image (unlike ConstraintManager's list/grid content limits) - the host scales/clips as
        // needed. This is a conservative default pending real head-unit/DHU size verification.
        private const val JUNCTION_IMAGE_WIDTH_PX = 480
        private const val JUNCTION_IMAGE_HEIGHT_PX = 180
    }
}
