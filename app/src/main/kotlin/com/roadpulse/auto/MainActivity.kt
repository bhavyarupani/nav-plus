package com.roadpulse.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.navigation.SupportNavigationFragment
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.widget.PlaceAutocomplete
import com.google.android.libraries.places.widget.PlaceAutocompleteActivity
import com.roadpulse.auto.alerts.CameraDataRefreshCoordinator
import com.roadpulse.auto.alerts.CameraDataRefreshScheduler
import com.roadpulse.auto.alerts.CameraDataSource
import com.roadpulse.auto.alerts.NearbyOpenGatsoPoi
import com.roadpulse.auto.alerts.OfficialCameraDataUpdater
import com.roadpulse.auto.alerts.OfficialCameraRepository
import com.roadpulse.auto.alerts.OpenGatsoDataUpdater
import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.alerts.OpenGatsoRepository
import com.roadpulse.auto.alerts.OpenStreetMapCameraRepository
import com.roadpulse.auto.alerts.mergeCameraSources
import com.roadpulse.auto.car.DrivingSessionState
import com.roadpulse.auto.destination.SelectedDestination
import com.roadpulse.auto.destination.SelectedDestinationStore
import com.roadpulse.auto.map.MapMarkerIconFactory
import com.roadpulse.auto.map.RoadPulseMapTheme
import com.roadpulse.auto.map.SpeedLimitRoadStyle
import com.roadpulse.auto.quota.GoogleUsageGuard
import com.roadpulse.auto.quota.QuotaDecision
import com.roadpulse.auto.settings.DisplayFilterStore
import com.roadpulse.auto.settings.DisplayLayer
import com.roadpulse.auto.settings.DrivingContext
import com.roadpulse.auto.settings.RoadSignFilterStore
import com.roadpulse.auto.settings.RouteStyle
import com.roadpulse.auto.settings.RouteStylePreferencesStore
import com.roadpulse.auto.stops.RouteStopMode
import com.roadpulse.auto.stops.RouteStopPreferencesStore
import com.roadpulse.auto.traffic.AutobahnFacilityRepository
import com.roadpulse.auto.traffic.AutobahnTrafficRepository
import com.roadpulse.auto.traffic.DwdRoadWeatherRepository
import com.roadpulse.auto.traffic.OpenChargeMapRepository
import com.roadpulse.auto.traffic.OpenStreetMapRoadInfrastructureRepository
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.traffic.RoadFacility
import com.roadpulse.auto.traffic.RoadFacilityResult
import com.roadpulse.auto.traffic.RoadFacilityType
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureResult
import com.roadpulse.auto.traffic.RoadInfrastructureType
import com.roadpulse.auto.traffic.RoadWeatherResult
import com.roadpulse.auto.traffic.TrafficEvent
import com.roadpulse.auto.traffic.TrafficEventResult
import com.roadpulse.auto.traffic.TrafficEventType
import com.roadpulse.auto.traffic.TrafficSnapshotStore
import com.roadpulse.auto.traffic.WeatherWarning
import com.roadpulse.auto.traffic.WeatherWarningResult
import com.roadpulse.auto.traffic.displayName
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

class MainActivity : FragmentActivity() {
    private lateinit var openGatsoUpdater: OpenGatsoDataUpdater
    private lateinit var openStreetMapCameraRepository: OpenStreetMapCameraRepository
    private lateinit var officialCameraDataUpdater: OfficialCameraDataUpdater
    private lateinit var officialCameraRepository: OfficialCameraRepository
    private lateinit var cameraDataRefreshCoordinator: CameraDataRefreshCoordinator
    private lateinit var cameraStatus: TextView
    private lateinit var roadStatus: TextView
    private lateinit var cameraToggleButton: Button
    private lateinit var destinationStatus: TextView
    private lateinit var startNavigationButton: Button
    private lateinit var supermarketStopButton: Button
    private lateinit var fuelStopButton: Button
    private lateinit var routeStyleButton: Button
    private lateinit var usageGuard: GoogleUsageGuard
    private lateinit var destinationStore: SelectedDestinationStore
    private lateinit var roadInfrastructureRepository: OpenStreetMapRoadInfrastructureRepository
    private lateinit var autobahnTrafficRepository: AutobahnTrafficRepository
    private lateinit var autobahnFacilityRepository: AutobahnFacilityRepository
    private lateinit var openChargeMapRepository: OpenChargeMapRepository
    private lateinit var dwdRoadWeatherRepository: DwdRoadWeatherRepository
    private lateinit var trafficSnapshotStore: TrafficSnapshotStore
    private lateinit var routeStopPreferences: RouteStopPreferencesStore
    private lateinit var routeStylePreferences: RouteStylePreferencesStore
    private lateinit var displayFilters: DisplayFilterStore
    private lateinit var roadSignFilters: RoadSignFilterStore
    private var phoneMap: GoogleMap? = null
    private var destinationMarker: Marker? = null
    private val cameraMarkers = mutableListOf<Marker>()
    private val cameraClusterPositions = mutableMapOf<Marker, LatLng>()
    private val cameraIconCache = mutableMapOf<String, BitmapDescriptor>()
    private val mapMarkerIcons by lazy { MapMarkerIconFactory(this) }
    private val roadMarkers = mutableListOf<Marker>()
    private val trafficPolylines = mutableListOf<Polyline>()
    private var cameraSearchInProgress = false
    private var cameraLayerEnabled = true
    private var hasFramedCameraLayer = false
    private var lastStationaryLocation: Location? = null
    private var cameraViewportQueryId = 0
    private var openGatsoRefreshInProgress = false
    private var roadSearchInProgress = false
    private var roadViewportQueryId = 0

    private val placeAutocompleteLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data
            when {
                result.resultCode == PlaceAutocompleteActivity.RESULT_OK && data != null -> {
                    handlePlaceSelection(data)
                }
                result.resultCode == PlaceAutocompleteActivity.RESULT_ERROR && data != null -> {
                    destinationStatus.text =
                        "Search failed: " +
                        (
                            PlaceAutocomplete.getResultStatusFromIntent(data)?.statusMessage
                                ?: "unknown error"
                        )
                }
                result.resultCode == Activity.RESULT_CANCELED -> Unit
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usageGuard = GoogleUsageGuard(this)
        destinationStore = SelectedDestinationStore(this)
        openGatsoUpdater = OpenGatsoDataUpdater(this)
        openStreetMapCameraRepository = OpenStreetMapCameraRepository(this)
        officialCameraDataUpdater = OfficialCameraDataUpdater(this)
        officialCameraRepository = OfficialCameraRepository(this)
        cameraDataRefreshCoordinator = CameraDataRefreshCoordinator(this)
        roadInfrastructureRepository = OpenStreetMapRoadInfrastructureRepository(this)
        autobahnTrafficRepository = AutobahnTrafficRepository(this)
        autobahnFacilityRepository = AutobahnFacilityRepository(this)
        openChargeMapRepository = OpenChargeMapRepository(this)
        dwdRoadWeatherRepository = DwdRoadWeatherRepository(this)
        trafficSnapshotStore = TrafficSnapshotStore(this)
        routeStopPreferences = RouteStopPreferencesStore(this)
        routeStylePreferences = RouteStylePreferencesStore(this)
        displayFilters = DisplayFilterStore(this)
        roadSignFilters = RoadSignFilterStore(this)
        CameraDataRefreshScheduler.schedule(this)
        cameraLayerEnabled = displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.SPEED_CAMERAS)

        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        val mapContainer = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(
            mapContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val topCard = buildTopCard()
        root.addView(
            topCard,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).also { it.setMargins(dp(16), dp(12), dp(16), 0) },
        )
        ViewCompat.setOnApplyWindowInsetsListener(topCard) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (view.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = top + dp(12)
                view.layoutParams = this
            }
            insets
        }

        val bottomCard = buildBottomCard()
        root.addView(
            bottomCard,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ).also { it.setMargins(dp(16), 0, dp(16), dp(16)) },
        )
        ViewCompat.setOnApplyWindowInsetsListener(bottomCard) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (view.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bottom + dp(16)
                view.layoutParams = this
            }
            insets
        }

        setContentView(root)

        val mapFragment =
            supportFragmentManager.findFragmentByTag(PHONE_MAP_TAG)
                as? SupportNavigationFragment
                ?: SupportNavigationFragment().also { fragment ->
                    supportFragmentManager
                        .beginTransaction()
                        .replace(mapContainer.id, fragment, PHONE_MAP_TAG)
                        .commitNow()
                }
        mapFragment.getMapAsync { readyMap ->
            phoneMap = readyMap
            RoadPulseMapTheme.apply(this, readyMap)
            readyMap.uiSettings.isCompassEnabled = true
            readyMap.uiSettings.isZoomControlsEnabled = false
            readyMap.uiSettings.isMapToolbarEnabled = false
            readyMap.setPadding(0, dp(150), 0, dp(245))
            readyMap.setOnCameraIdleListener {
                if (cameraLayerEnabled &&
                    !cameraSearchInProgress &&
                    lastStationaryLocation != null
                ) {
                    refreshVisibleCameraLayer()
                }
                if (anyRoadLayerEnabled() && !roadSearchInProgress) refreshVisibleRoadContext()
            }
            readyMap.setOnMarkerClickListener { marker ->
                val clusterPosition = cameraClusterPositions[marker]
                if (clusterPosition != null) {
                    readyMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            clusterPosition,
                            (readyMap.cameraPosition.zoom + CLUSTER_ZOOM_STEP)
                                .coerceAtMost(MAX_MAP_ZOOM),
                        ),
                    )
                    true
                } else {
                    false
                }
            }
            destinationStore.load()?.let(::showDestinationOnPhoneMap)
            centerPhoneMap()
            if (cameraLayerEnabled) beginParkedCameraSearch()
        }
        refreshCameraDataAutomatically()
    }

    private fun buildTopCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedPanel(panelColor, 22)
            elevation = dp(12).toFloat()

            val heading =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label("My Maps", 21f, Color.WHITE).apply {
                            setTypeface(typeface, Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        label("EXPLORE", 11f, accentColor).apply {
                            setTypeface(typeface, Typeface.BOLD)
                            letterSpacing = .12f
                        },
                    )
                }
            addView(heading)
            addView(
                makeButton(
                    "Search places and destinations",
                    primary = false,
                    iconRes = R.drawable.ic_search_action,
                ) {
                    launchDestinationSearch()
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 16f
                },
                matchWidth(top = 10),
            )
        }

    private fun buildBottomCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = roundedPanel(panelColor, 24)
            elevation = dp(14).toFloat()

            val selected = destinationStore.load()
            addView(
                label("DESTINATION", 10f, accentColor).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = .12f
                },
            )
            destinationStatus =
                label(
                    selected?.let { "${it.title}\n${it.address}" } ?: "Where do you want to go?",
                    17f,
                    Color.WHITE,
                ).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }
            addView(destinationStatus, matchWidth(top = 5))

            startNavigationButton =
                makeButton("Start navigation", primary = true) {
                    startSelectedNavigation()
                }.apply { isEnabled = selected != null }
            addView(startNavigationButton, matchWidth(top = 12))

            routeStyleButton =
                compactButton(routeStyleButtonText()) {
                    routeStylePreferences.set(routeStylePreferences.load().next())
                    routeStyleButton.text = routeStyleButtonText()
                }
            addView(routeStyleButton, matchWidth(top = 8))

            addView(
                label("SMART STOPS", 10f, accentColor).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = .12f
                },
                matchWidth(top = 11),
            )
            val stopControls =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
            supermarketStopButton =
                compactButton(supermarketStopButtonText()) {
                    showRouteStopModeDialog(supermarket = true)
                }
            fuelStopButton =
                compactButton(fuelStopButtonText()) {
                    showRouteStopModeDialog(supermarket = false)
                }
            stopControls.addView(supermarketStopButton, weightedControl())
            stopControls.addView(fuelStopButton, weightedControl(8))
            addView(stopControls, matchWidth(top = 5))

            val controls =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
            controls.addView(
                compactButton("Locate", iconRes = R.drawable.ic_locate_action) { centerPhoneMap() },
                weightedControl(),
            )
            controls.addView(
                compactButton("Destination", iconRes = R.drawable.ic_destination_action) {
                    centerDestinationOnPhoneMap()
                },
                weightedControl(6),
            )
            cameraToggleButton =
                compactButton(cameraButtonText(), iconRes = R.drawable.ic_camera_layer_action) {
                    toggleCameraLayer()
                }
            controls.addView(cameraToggleButton, weightedControl(6))
            controls.addView(
                compactButton("Settings", iconRes = R.drawable.ic_settings_action) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                },
                weightedControl(6),
            )
            addView(controls, matchWidth(top = 10))

            cameraStatus =
                label(initialCameraStatus(), 11f, cameraAccentColor).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            addView(cameraStatus, matchWidth(top = 10))

            roadStatus =
                label(initialRoadStatus(), 11f, roadAccentColor).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            addView(roadStatus, matchWidth(top = 3))
        }

    override fun onResume() {
        super.onResume()
        if (!::cameraStatus.isInitialized) return
        val connected = DrivingSessionState.isAndroidAutoConnected
        cameraToggleButton.isEnabled = !connected
        val camerasNowEnabled = displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.SPEED_CAMERAS)
        if (camerasNowEnabled != cameraLayerEnabled) {
            cameraLayerEnabled = camerasNowEnabled
            cameraToggleButton.text = cameraButtonText()
        }
        if (connected) {
            cameraViewportQueryId++
            clearCameraMarkers()
            cameraStatus.text = "Camera layer hidden while Android Auto is connected"
        } else if (cameraLayerEnabled && !cameraSearchInProgress) {
            beginParkedCameraSearch()
        } else if (!cameraLayerEnabled) {
            cameraViewportQueryId++
            clearCameraMarkers()
            cameraStatus.text = "Camera layer off"
        }
        if (anyRoadLayerEnabled()) {
            refreshVisibleRoadContext()
        } else {
            roadViewportQueryId++
            clearRoadContext()
            roadStatus.text = "Road context layer off"
        }
        destinationStore.load()?.let { selected ->
            destinationStatus.text = "${selected.title}\n${selected.address}"
            startNavigationButton.isEnabled = true
        }
        supermarketStopButton.text = supermarketStopButtonText()
        fuelStopButton.text = fuelStopButtonText()
    }

    private fun launchDestinationSearch() {
        when (usageGuard.searchRequests.tryConsume()) {
            is QuotaDecision.Blocked -> {
                destinationStatus.text = "The 1,000-search monthly safety limit is reached."
                return
            }
            is QuotaDecision.Allowed -> Unit
        }

        val apiKey = googleMapsApiKey()
        if (apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") {
            destinationStatus.text = "The local Google Maps API key is not configured."
            return
        }
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
        }
        val intent =
            PlaceAutocomplete
                .IntentBuilder()
                .setAutocompleteSessionToken(AutocompleteSessionToken.newInstance())
                .build(this)
        placeAutocompleteLauncher.launch(intent)
    }

    private fun handlePlaceSelection(data: Intent) {
        val prediction =
            PlaceAutocomplete.getPredictionFromIntent(data) ?: run {
                destinationStatus.text = "No destination was returned by Google."
                return
            }
        val sessionToken = PlaceAutocomplete.getSessionTokenFromIntent(data)
        val requestBuilder =
            FetchPlaceRequest.builder(
                prediction.placeId,
                listOf(
                    Place.Field.ID,
                    Place.Field.DISPLAY_NAME,
                    Place.Field.FORMATTED_ADDRESS,
                    Place.Field.LOCATION,
                ),
            )
        if (sessionToken != null) requestBuilder.setSessionToken(sessionToken)
        destinationStatus.text = "Loading destination…"
        Places
            .createClient(this)
            .fetchPlace(requestBuilder.build())
            .addOnSuccessListener { response ->
                val place = response.place
                val selected =
                    SelectedDestination(
                        placeId = prediction.placeId,
                        title =
                            place.displayName.orEmpty().ifBlank {
                                prediction.getPrimaryText(null).toString()
                            },
                        address = place.formattedAddress.orEmpty(),
                        latitude = place.location?.latitude,
                        longitude = place.location?.longitude,
                    )
                destinationStore.save(selected)
                destinationStatus.text = "${selected.title}\n${selected.address}"
                startNavigationButton.isEnabled = true
                showDestinationOnPhoneMap(selected)
                centerDestinationOnPhoneMap()
            }.addOnFailureListener { error ->
                destinationStatus.text = "Unable to load destination: ${error.message}"
            }
    }

    private fun startSelectedNavigation() {
        if (destinationStore.load() == null) {
            destinationStatus.text = "Search for a destination first."
            return
        }
        if (usageGuard.navigationDestinations.snapshot().isExhausted) {
            destinationStatus.text = "The 1,000-destination monthly safety limit is reached."
            return
        }
        startActivity(Intent(this, NavigationActivity::class.java))
    }

    private fun toggleCameraLayer() {
        cameraLayerEnabled = !cameraLayerEnabled
        displayFilters.setEnabled(DrivingContext.PARKED, DisplayLayer.SPEED_CAMERAS, cameraLayerEnabled)
        cameraToggleButton.text = cameraButtonText()
        if (cameraLayerEnabled) {
            hasFramedCameraLayer = false
            beginParkedCameraSearch()
        } else {
            cameraViewportQueryId++
            clearCameraMarkers()
            cameraStatus.text = "Camera layer off"
        }
    }

    @Suppress("DEPRECATION")
    private fun googleMapsApiKey(): String =
        packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString("com.google.android.geo.API_KEY")
            .orEmpty()

    @SuppressLint("MissingPermission")
    private fun centerPhoneMap() {
        val map = phoneMap ?: return
        val selected = destinationStore.load()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val manager = getSystemService(LocationManager::class.java)
            val current =
                sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }.maxByOrNull(Location::getTime)
            if (current != null) {
                map.isMyLocationEnabled = true
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(current.latitude, current.longitude),
                        15f,
                    ),
                )
                selected?.let(::showDestinationOnPhoneMap)
                return
            }
        }
        val destinationPosition =
            selected?.let { destination ->
                if (destination.latitude != null && destination.longitude != null) {
                    LatLng(destination.latitude, destination.longitude)
                } else {
                    null
                }
            }
        if (destinationPosition != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(destinationPosition, 14f))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_MAP_CENTER, 5f))
        }
    }

    private fun showDestinationOnPhoneMap(destination: SelectedDestination) {
        val latitude = destination.latitude ?: return
        val longitude = destination.longitude ?: return
        destinationMarker?.remove()
        destinationMarker =
            phoneMap?.addMarker(
                MarkerOptions()
                    .position(LatLng(latitude, longitude))
                    .title(destination.title)
                    .snippet(destination.address)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
    }

    private fun centerDestinationOnPhoneMap() {
        val destination =
            destinationStore.load() ?: run {
                destinationStatus.text = "Search for a destination first."
                return
            }
        val latitude = destination.latitude ?: return
        val longitude = destination.longitude ?: return
        showDestinationOnPhoneMap(destination)
        phoneMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 15f),
        )
    }

    private fun beginParkedCameraSearch() {
        if (!cameraLayerEnabled || cameraSearchInProgress) return
        if (DrivingSessionState.isAndroidAutoConnected) {
            clearCameraMarkers()
            cameraStatus.text = "Camera layer hidden while Android Auto is connected"
            return
        }
        if (!hasOfflineCameraData()) {
            clearCameraMarkers()
            cameraStatus.text = "Camera data needed · Open More to download"
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cameraStatus.text = "Allow location to show nearby cameras"
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
            return
        }
        locateForParkedSearch()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            centerPhoneMap()
            locateForParkedSearch()
        } else {
            cameraStatus.text = "Location permission is needed for nearby cameras"
        }
    }

    @SuppressLint("MissingPermission")
    private fun locateForParkedSearch() {
        if (DrivingSessionState.isAndroidAutoConnected || cameraSearchInProgress) return
        cameraSearchInProgress = true
        cameraToggleButton.isEnabled = false
        cameraStatus.text = "Finding nearby cameras…"

        val manager = getSystemService(LocationManager::class.java)
        val provider =
            when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
        if (provider == null) {
            finishCameraSearch("Turn on phone location to show nearby cameras")
            return
        }

        val lastLocation = manager.getLastKnownLocation(provider)
        if (lastLocation != null &&
            System.currentTimeMillis() - lastLocation.time <= LOCATION_MAX_AGE_MILLIS
        ) {
            showNearbyEnforcementLocations(lastLocation)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, { command -> runOnUiThread(command) }) { location ->
                if (location == null) {
                    finishCameraSearch("Unable to find your location. Try again outdoors.")
                } else {
                    showNearbyEnforcementLocations(location)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(
                provider,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        showNearbyEnforcementLocations(location)
                    }
                },
                Looper.getMainLooper(),
            )
        }
    }

    private fun showNearbyEnforcementLocations(location: Location) {
        if (isLikelyMoving(location)) {
            lastStationaryLocation = null
            cameraViewportQueryId++
            clearCameraMarkers()
            finishCameraSearch("Camera layer paused while the phone is moving")
            return
        }
        lastStationaryLocation = location
        if (hasFramedCameraLayer) {
            finishCameraSearch("Loading cameras in the visible map area…")
            refreshVisibleCameraLayer()
            return
        }
        Thread {
            val results =
                runCatching {
                    val openGatso =
                        OpenGatsoRepository(openGatsoUpdater.currentDataFile())
                            .nearestEnforcementLocations(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                limit = MAX_CAMERA_MARKERS,
                                maximumDistanceMeters = CAMERA_RADIUS_METERS,
                            )
                    val official =
                        officialCameraRepository.nearestEnforcementLocations(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            maximumDistanceMeters = CAMERA_RADIUS_METERS,
                        )
                    mergeCameraSources(openGatso, official).take(MAX_CAMERA_MARKERS)
                }
            runOnUiThread {
                if (DrivingSessionState.isAndroidAutoConnected || !cameraLayerEnabled) {
                    clearCameraMarkers()
                    finishCameraSearch("Camera layer hidden while driving")
                    return@runOnUiThread
                }
                results
                    .onSuccess { nearby -> plotInitialCameraMarkers(nearby, location) }
                    .onFailure { error ->
                        finishCameraSearch("Unable to read camera data: ${error.message}")
                    }
            }
        }.start()
    }

    private fun plotInitialCameraMarkers(
        nearby: List<NearbyOpenGatsoPoi>,
        location: Location,
    ) {
        clearCameraMarkers()
        val map = phoneMap
        if (map != null) {
            addCameraMarkers(map, nearby)
            if (nearby.isNotEmpty() && !hasFramedCameraLayer) {
                val pointsToFrame =
                    nearby
                        .filter { it.distanceMeters <= CAMERA_FRAME_RADIUS_METERS }
                        .take(8)
                        .ifEmpty { nearby.take(1) }
                val bounds =
                    LatLngBounds
                        .Builder()
                        .include(LatLng(location.latitude, location.longitude))
                        .also { builder ->
                            pointsToFrame.forEach { item ->
                                builder.include(LatLng(item.poi.latitude, item.poi.longitude))
                            }
                        }.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(48)))
                hasFramedCameraLayer = true
            }
        }
        val message =
            if (nearby.isEmpty()) {
                "No cameras found within ${CAMERA_RADIUS_METERS / 1_000} km"
            } else {
                "${nearby.size} nearby cameras on map · stationary view"
            }
        finishCameraSearch(message)
    }

    private fun refreshVisibleCameraLayer() {
        val map = phoneMap ?: return
        if (!cameraLayerEnabled || DrivingSessionState.isAndroidAutoConnected) return
        if (map.cameraPosition.zoom < MIN_CAMERA_LAYER_ZOOM) {
            cameraViewportQueryId++
            clearCameraMarkers()
            cameraStatus.text = "Zoom in to display every camera in the visible area"
            return
        }

        val bounds = map.projection.visibleRegion.latLngBounds
        val center = map.cameraPosition.target
        val zoom = map.cameraPosition.zoom
        val queryId = ++cameraViewportQueryId
        cameraStatus.text = "Combining camera sources for this map area…"
        Thread {
            val results =
                runCatching {
                    val openGatso =
                        OpenGatsoRepository(openGatsoUpdater.currentDataFile())
                            .enforcementLocationsInBounds(
                                southLatitude = bounds.southwest.latitude,
                                westLongitude = bounds.southwest.longitude,
                                northLatitude = bounds.northeast.latitude,
                                eastLongitude = bounds.northeast.longitude,
                                referenceLatitude = center.latitude,
                                referenceLongitude = center.longitude,
                            )
                    val official =
                        officialCameraRepository.enforcementLocationsInBounds(
                            southLatitude = bounds.southwest.latitude,
                            westLongitude = bounds.southwest.longitude,
                            northLatitude = bounds.northeast.latitude,
                            eastLongitude = bounds.northeast.longitude,
                            referenceLatitude = center.latitude,
                            referenceLongitude = center.longitude,
                        )
                    val osmResult =
                        if (zoom >= MIN_OPENSTREETMAP_QUERY_ZOOM) {
                            runCatching {
                                openStreetMapCameraRepository.enforcementLocationsInBounds(
                                    southLatitude = bounds.southwest.latitude,
                                    westLongitude = bounds.southwest.longitude,
                                    northLatitude = bounds.northeast.latitude,
                                    eastLongitude = bounds.northeast.longitude,
                                    referenceLatitude = center.latitude,
                                    referenceLongitude = center.longitude,
                                )
                            }
                        } else {
                            Result.success(emptyList())
                        }
                    CameraLayerResult(
                        cameras =
                            mergeCameraSources(
                                mergeCameraSources(openGatso, official),
                                osmResult.getOrDefault(emptyList()),
                            ),
                        openStreetMapChecked =
                            zoom >= MIN_OPENSTREETMAP_QUERY_ZOOM &&
                                osmResult.isSuccess,
                        openStreetMapUsedSavedData =
                            zoom >= MIN_OPENSTREETMAP_QUERY_ZOOM &&
                                osmResult.isSuccess &&
                                openStreetMapCameraRepository.lastResultUsedSavedData,
                        latestTimestampMillis =
                            maxOf(
                                openGatsoUpdater.currentDataFile().lastModified(),
                                officialCameraRepository.latestTimestampMillis,
                                if (osmResult.isSuccess) {
                                    openStreetMapCameraRepository.lastResultTimestampMillis
                                } else {
                                    0L
                                },
                            ),
                    )
                }
            runOnUiThread {
                if (queryId != cameraViewportQueryId) return@runOnUiThread
                if (!cameraLayerEnabled || DrivingSessionState.isAndroidAutoConnected) {
                    clearCameraMarkers()
                    return@runOnUiThread
                }
                results
                    .onSuccess(::plotVisibleCameraMarkers)
                    .onFailure { error ->
                        cameraStatus.text = "Unable to read camera data: ${error.message}"
                    }
            }
        }.start()
    }

    private fun plotVisibleCameraMarkers(result: CameraLayerResult) {
        val cameras = result.cameras
        clearCameraMarkers()
        val grouped = phoneMap?.let { map -> addCameraMarkers(map, cameras) } == true
        val sources = cameras.flatMap { it.sources }.toSet()
        val sourceText =
            sources
                .sortedBy(CameraDataSource::ordinal)
                .joinToString(" + ") { source ->
                    if (source == CameraDataSource.OPENSTREETMAP && result.openStreetMapUsedSavedData) {
                        "saved ${source.shortName}"
                    } else {
                        source.shortName
                    }
                }.ifBlank { if (result.openStreetMapChecked) "checked sources" else "offline sources" }
        val freshness =
            result.latestTimestampMillis
                .takeIf { it > 0L }
                ?.let {
                    " · data ${android.text.format.DateFormat.getTimeFormat(this).format(Date(it))}"
                }.orEmpty()
        cameraStatus.text =
            if (cameras.isEmpty()) {
                "No cameras in the visible map area · $sourceText$freshness"
            } else if (grouped) {
                "${cameras.size} cameras · $sourceText$freshness · tap a group"
            } else {
                "${cameras.size} cameras in view · $sourceText$freshness"
            }
    }

    private fun addCameraMarkers(
        map: GoogleMap,
        cameras: List<NearbyOpenGatsoPoi>,
    ): Boolean {
        val shouldGroup =
            cameras.size > CAMERA_GROUP_THRESHOLD &&
                map.cameraPosition.zoom < CAMERA_GROUP_MAX_ZOOM
        if (shouldGroup) {
            val cellSize = dp(CAMERA_GROUP_CELL_DP).coerceAtLeast(1)
            val groups =
                cameras.groupBy { item ->
                    val point =
                        map.projection.toScreenLocation(
                            LatLng(item.poi.latitude, item.poi.longitude),
                        )
                    Pair(point.x / cellSize, point.y / cellSize)
                }
            groups.values.forEach { group ->
                if (group.size == 1) {
                    addCameraMarker(map, group.first())
                } else {
                    val position =
                        LatLng(
                            group.map { it.poi.latitude }.average(),
                            group.map { it.poi.longitude }.average(),
                        )
                    val marker =
                        map.addMarker(
                            MarkerOptions()
                                .position(position)
                                .title("${group.size} cameras")
                                .snippet("Tap to zoom in")
                                .icon(cameraGroupIcon(group.size))
                                .anchor(.5f, .5f)
                                .zIndex(2f),
                        )
                    if (marker != null) {
                        cameraMarkers += marker
                        cameraClusterPositions[marker] = position
                    }
                }
            }
            return true
        }
        cameras.forEach { item ->
            addCameraMarker(map, item)
        }
        return false
    }

    private fun addCameraMarker(
        map: GoogleMap,
        item: NearbyOpenGatsoPoi,
    ) {
        val marker =
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(item.poi.latitude, item.poi.longitude))
                    .title(cameraTitle(item))
                    .snippet(
                        String.format(
                            Locale.US,
                            "%.1f km from center · %s%s",
                            item.distanceMeters / 1_000.0,
                            item.sources.joinToString(" + ") { it.displayName },
                            cameraRecordDetails(item),
                        ),
                    ).icon(cameraMarkerIcon(item))
                    .anchor(.5f, .5f),
            )
        if (marker != null) cameraMarkers += marker
    }

    private fun cameraTitle(item: NearbyOpenGatsoPoi): String {
        val type =
            when (item.poi.type) {
                OpenGatsoPoiType.SPEED_CAMERA -> "Speed camera"
                OpenGatsoPoiType.AVERAGE_SPEED_CAMERA -> "Average-speed camera"
                OpenGatsoPoiType.RED_LIGHT_CAMERA -> "Red-light camera"
                OpenGatsoPoiType.RAIL_CROSSING_CAMERA -> "Rail-crossing camera"
                else -> "Enforcement point"
            }
        return item.poi.speedLimitKph?.let { "$type · $it km/h" } ?: type
    }

    private fun cameraRecordDetails(item: NearbyOpenGatsoPoi): String {
        val details =
            item.sourceRecords
                .flatMap { record ->
                    listOfNotNull(
                        record.roadName?.let { "Road $it" },
                        record.direction?.let { "Direction $it" },
                        record.areaName,
                        record.locationDescription,
                        record.installedDate?.let { "Installed $it" },
                        record.sourceId?.let { "ID $it" },
                    )
                }.map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        return if (details.isEmpty()) "" else "\n${details.joinToString(" · ")}"
    }

    private fun cameraMarkerIcon(item: NearbyOpenGatsoPoi): BitmapDescriptor = mapMarkerIcons.camera(item.poi.type, item.poi.speedLimitKph)

    private fun cameraGroupIcon(count: Int): BitmapDescriptor {
        val text = if (count > 999) "999+" else count.toString()
        return cameraIconCache.getOrPut("group:$text") {
            val size = dp(40)
            val inset = dp(3).toFloat()
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(13, 42, 61)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - inset, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.color = accentColor
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - inset, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = if (text.length <= 2) dp(13).toFloat() else dp(10).toFloat()
            val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(text, size / 2f, baseline, paint)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private fun isLikelyMoving(location: Location): Boolean {
        val isFresh = System.currentTimeMillis() - location.time <= MOVEMENT_MAX_AGE_MILLIS
        return isFresh && location.hasSpeed() && location.speed * 3.6f >= MOVING_SPEED_KPH
    }

    private fun clearCameraMarkers() {
        cameraMarkers.forEach(Marker::remove)
        cameraMarkers.clear()
        cameraClusterPositions.clear()
    }

    private fun finishCameraSearch(message: String) {
        cameraSearchInProgress = false
        cameraToggleButton.isEnabled = !DrivingSessionState.isAndroidAutoConnected
        cameraToggleButton.text = cameraButtonText()
        cameraStatus.text = message
    }

    private fun initialCameraStatus(): String =
        when {
            !cameraLayerEnabled -> "Camera layer off"
            !hasOfflineCameraData() -> "Camera data needed · Open Settings to download"
            else -> "Loading stationary camera layer…"
        }

    private fun cameraButtonText(): String = if (cameraLayerEnabled) "Cameras · on" else "Cameras · off"

    private fun refreshCameraDataAutomatically() {
        if (openGatsoRefreshInProgress || !cameraDataRefreshCoordinator.isRefreshDue()) return
        openGatsoRefreshInProgress = true
        val hasExistingData = hasOfflineCameraData()
        if (!hasExistingData) cameraStatus.text = "Downloading current offline camera data…"
        Thread {
            runCatching { cameraDataRefreshCoordinator.refresh() }
                .onSuccess { result ->
                    runOnUiThread {
                        openGatsoRefreshInProgress = false
                        cameraStatus.text =
                            if (result.failureCount == 0) {
                                "Camera data refreshed · ${result.updatedSourceCount} sources"
                            } else {
                                "${result.updatedSourceCount} sources refreshed · " +
                                    "${result.failureCount} using saved data"
                            }
                        if (cameraLayerEnabled) {
                            if (lastStationaryLocation != null) {
                                refreshVisibleCameraLayer()
                            } else {
                                beginParkedCameraSearch()
                            }
                        }
                    }
                }.onFailure {
                    runOnUiThread {
                        openGatsoRefreshInProgress = false
                        cameraStatus.text =
                            if (hasExistingData) {
                                "Using saved camera data · refresh unavailable"
                            } else {
                                "Camera-data download unavailable · try again in More"
                            }
                    }
                }
        }.start()
    }

    private fun hasOfflineCameraData(): Boolean =
        openGatsoUpdater.currentDataFile().isFile ||
            officialCameraDataUpdater.statuses().isNotEmpty()

    private fun currentCameraDataStatus(): String {
        val lines = mutableListOf<String>()
        openGatsoUpdater.currentDataFile().takeIf(File::isFile)?.let { file ->
            lines += "Open-GATSO: ${formatRelativeTime(file.lastModified())} " +
                "(${formatStorageSize(file.length())})"
        }
        officialCameraDataUpdater.statuses().forEach { status ->
            val count =
                status.pointCount
                    .takeIf { it > 0 }
                    ?.let { " · $it points" }
                    .orEmpty()
            lines += "${status.feed.source.displayName}: " +
                "${formatRelativeTime(status.file.lastModified())}$count"
        }
        if (openStreetMapCameraRepository.storedRegionCount > 0) {
            val timestamp =
                openStreetMapCameraRepository.lastResultTimestampMillis
                    .takeIf { it > 0L }
                    ?.let { " · data ${formatRelativeTime(it)}" }
                    .orEmpty()
            lines += "OpenStreetMap: ${openStreetMapCameraRepository.storedRegionCount} saved regions " +
                "(${formatStorageSize(openStreetMapCameraRepository.storedBytes)})$timestamp"
        }
        return lines.joinToString("\n").ifBlank { "Offline camera data has not been downloaded." }
    }

    private fun anyRoadLayerEnabled(): Boolean =
        ROAD_LAYER_BUNDLE.any {
            displayFilters.isEnabled(DrivingContext.PARKED, it)
        }

    private fun refreshVisibleRoadContext() {
        val map = phoneMap ?: return
        if (!anyRoadLayerEnabled() || roadSearchInProgress) return
        if (map.cameraPosition.zoom < MIN_ROAD_CONTEXT_ZOOM) {
            roadViewportQueryId++
            clearRoadContext()
            roadStatus.text = "Zoom in for road signs, signals, and live traffic"
            return
        }
        val bounds = map.projection.visibleRegion.latLngBounds
        val roadQueryCenter = map.cameraPosition.target
        val queryId = ++roadViewportQueryId
        val showRoadSigns = displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.ROAD_SIGNS)
        val showAutobahnTraffic = displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.AUTOBAHN_TRAFFIC)
        val showAutobahnFacilities =
            displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.AUTOBAHN_FACILITIES)
        val showWeather = displayFilters.isEnabled(DrivingContext.PARKED, DisplayLayer.WEATHER)
        roadSearchInProgress = true
        roadStatus.text = "Checking mapped signs and live Autobahn traffic…"
        Thread {
            // Infrastructure/weather/warnings are independent and run concurrently; traffic and
            // facilities depend on infrastructure's autobahnRefs so they start once it resolves.
            val result =
                runCatching {
                    val infrastructureFuture =
                        CompletableFuture.supplyAsync {
                            if (!showRoadSigns) {
                                return@supplyAsync Result.success(RoadInfrastructureResult(emptyList(), emptySet(), 0L, false))
                            }
                            runCatching {
                                roadInfrastructureRepository.infrastructureInBounds(
                                    southLatitude = bounds.southwest.latitude,
                                    westLongitude = bounds.southwest.longitude,
                                    northLatitude = bounds.northeast.latitude,
                                    eastLongitude = bounds.northeast.longitude,
                                )
                            }
                        }
                    val warningsFuture =
                        CompletableFuture.supplyAsync {
                            if (!showWeather) return@supplyAsync WeatherWarningResult(emptyList(), 0L, false)
                            runCatching {
                                dwdRoadWeatherRepository.warningsInBounds(
                                    south = bounds.southwest.latitude,
                                    west = bounds.southwest.longitude,
                                    north = bounds.northeast.latitude,
                                    east = bounds.northeast.longitude,
                                )
                            }.getOrElse { WeatherWarningResult(emptyList(), 0L, true) }
                        }
                    val weatherFuture =
                        CompletableFuture.supplyAsync {
                            if (!showWeather) return@supplyAsync null
                            runCatching {
                                dwdRoadWeatherRepository.roadForecastNear(
                                    latitude = roadQueryCenter.latitude,
                                    longitude = roadQueryCenter.longitude,
                                )
                            }.getOrNull()
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

                    val infrastructureAttempt = infrastructureFuture.get()
                    val infrastructure =
                        infrastructureAttempt.getOrElse {
                            RoadInfrastructureResult(emptyList(), emptySet(), 0L, true)
                        }
                    val trafficFuture =
                        CompletableFuture.supplyAsync {
                            if (!showAutobahnTraffic) return@supplyAsync Result.success(TrafficEventResult(emptyList(), 0L, false, 0))
                            runCatching { autobahnTrafficRepository.eventsForRoads(infrastructure.autobahnRefs) }
                        }
                    val facilitiesFuture =
                        CompletableFuture.supplyAsync {
                            if (!showAutobahnFacilities) return@supplyAsync RoadFacilityResult(emptyList(), 0L, false)
                            runCatching {
                                autobahnFacilityRepository.facilitiesForRoads(infrastructure.autobahnRefs)
                            }.getOrElse { RoadFacilityResult(emptyList(), 0L, true) }
                        }

                    val trafficAttempt = trafficFuture.get()
                    val traffic =
                        trafficAttempt.getOrElse {
                            TrafficEventResult(emptyList(), 0L, true, infrastructure.autobahnRefs.size)
                        }
                    val autobahnFacilities = facilitiesFuture.get()
                    val openChargeMapFacilities = openChargeMapFuture.get()
                    val facilities =
                        autobahnFacilities.copy(
                            facilities =
                                (autobahnFacilities.facilities + infrastructure.facilities + openChargeMapFacilities)
                                    .distinctBy(RoadFacility::id),
                            timestampMillis =
                                maxOf(
                                    autobahnFacilities.timestampMillis,
                                    infrastructure.timestampMillis,
                                ),
                            usedSavedData =
                                autobahnFacilities.usedSavedData ||
                                    infrastructure.usedSavedData,
                        )
                    val warnings = warningsFuture.get()
                    val weather = weatherFuture.get()
                    RoadContextResult(
                        infrastructure = infrastructure,
                        traffic =
                            traffic.copy(
                                events =
                                    traffic.events.filter { event ->
                                        event.geometry.any { coordinate ->
                                            coordinateIsInside(coordinate, bounds)
                                        }
                                    },
                            ),
                        facilities =
                            facilities.copy(
                                facilities =
                                    facilities.facilities.filter { facility ->
                                        coordinateIsInside(facility.coordinate, bounds)
                                    },
                            ),
                        weather = weather,
                        warnings = warnings,
                        mappedRoadDataUnavailable = showRoadSigns && infrastructureAttempt.isFailure,
                        liveTrafficUnavailable = showAutobahnTraffic && trafficAttempt.isFailure,
                    )
                }
            runOnUiThread {
                roadSearchInProgress = false
                if (queryId != roadViewportQueryId || !anyRoadLayerEnabled()) return@runOnUiThread
                result
                    .onSuccess(::plotRoadContext)
                    .onFailure { error ->
                        roadStatus.text = "Road context unavailable: ${error.message}"
                    }
            }
        }.start()
    }

    private fun plotRoadContext(result: RoadContextResult) {
        clearRoadContext()
        val map = phoneMap ?: return
        addSpeedLimitRoadColours(map, result.infrastructure.speedLimitSections)
        val points = selectRoadInfrastructurePoints(map, result.infrastructure.points)
        val mapEligiblePointCount =
            result.infrastructure.points.count {
                it.shouldDisplayOnMap && !it.isRouteGuidanceFeature
            }
        points.forEach { point ->
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                        .title(point.title)
                        .snippet(point.detail)
                        .icon(roadInfrastructureIcon(point))
                        .anchor(.5f, .5f)
                        .zIndex(1f),
                )?.let(roadMarkers::add)
        }
        result.traffic.events.forEach { event -> addTrafficEvent(map, event) }
        result.facilities.facilities.take(MAX_FACILITY_MARKERS).forEach { facility ->
            addRoadFacility(map, facility)
        }
        result.warnings.warnings.take(MAX_WEATHER_WARNING_MARKERS).forEach { warning ->
            addWeatherWarning(map, warning)
        }
        result.weather?.let { addRoadWeather(map, it) }
        trafficSnapshotStore.save(
            result.traffic.events,
            result.traffic.timestampMillis,
            result.traffic.usedSavedData,
        )

        val signalCount = points.count { it.type == RoadInfrastructureType.TRAFFIC_SIGNAL }
        val speedLimitCount = points.count { it.type == RoadInfrastructureType.SPEED_LIMIT_SIGN }
        val speedSectionCount = result.infrastructure.speedLimitSections.size
        val signCount = points.size - signalCount - speedLimitCount
        val trafficCount = result.traffic.events.size
        val facilityCount = result.facilities.facilities.size
        val restrooms =
            result.facilities.facilities.filter {
                it.type == RoadFacilityType.RESTROOM
            }
        val warningCount = result.warnings.warnings.size
        val eventLabel =
            if (
                result.infrastructure.usedSavedData || result.traffic.usedSavedData
            ) {
                "saved road events"
            } else {
                "live road events"
            }
        roadStatus.text =
            buildString {
                append(
                    "$speedLimitCount limit signs · $speedSectionCount coloured speed sections · " +
                        "$signCount safety points · $signalCount signals · " +
                        "$trafficCount $eventLabel · " +
                        "$facilityCount services",
                )
                if (restrooms.isNotEmpty()) {
                    val free =
                        restrooms.count {
                            it.restroomFeeStatus == com.roadpulse.auto.traffic.RestroomFeeStatus.FREE
                        }
                    val paid =
                        restrooms.count {
                            it.restroomFeeStatus == com.roadpulse.auto.traffic.RestroomFeeStatus.PAID
                        }
                    append(" · ${restrooms.size} WC ($free free, $paid paid)")
                }
                if (warningCount > 0) append(" · $warningCount weather warning(s)")
                append(" · all visible here; route-matched while driving")
                val risk = result.weather?.mostSevere
                if (risk != null) {
                    append(" · road ${risk.condition.displayName.lowercase()}")
                    risk.surfaceTemperatureC?.let { append(String.format(Locale.US, " %.0f°C", it)) }
                }
                if (mapEligiblePointCount > points.size) append(" · zoom in for all")
                if (signalCount > 0) append(" · signal phases unavailable")
                if (result.infrastructure.autobahnRefs.isEmpty()) append(" · no Autobahn in view")
                if (result.mappedRoadDataUnavailable) append(" · mapped-road feed unavailable")
                if (result.liveTrafficUnavailable) append(" · traffic feed unavailable")
            }
    }

    private fun addSpeedLimitRoadColours(
        map: GoogleMap,
        sections: List<com.roadpulse.auto.traffic.SpeedLimitRoadSection>,
    ) {
        sections.take(MAX_SPEED_LIMIT_ROAD_SECTIONS).forEach { section ->
            if (section.geometry.size < 2) return@forEach
            map
                .addPolyline(
                    PolylineOptions()
                        .addAll(section.geometry.map { LatLng(it.latitude, it.longitude) })
                        .color(SpeedLimitRoadStyle.colour(section))
                        .width(9f)
                        .zIndex(.2f),
                ).let(trafficPolylines::add)
        }
    }

    private fun addTrafficEvent(
        map: GoogleMap,
        event: TrafficEvent,
    ) {
        val start = event.start ?: return
        val end = event.end ?: start
        val colour = trafficEventColour(event.type)
        val detail =
            buildString {
                if (event.direction.isNotBlank()) append(event.direction)
                event.delayMinutes?.let {
                    if (isNotEmpty()) append(" · ")
                    append("+$it min")
                }
                if (event.detail.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(event.detail)
                }
            }
        map
            .addMarker(
                MarkerOptions()
                    .position(LatLng(start.latitude, start.longitude))
                    .title("${event.type.displayName} starts")
                    .snippet(detail)
                    .icon(mapMarkerIcons.trafficEvent(event.type, "S")),
            )?.let(roadMarkers::add)
        if (start != end) {
            map
                .addMarker(
                    MarkerOptions()
                        .position(LatLng(end.latitude, end.longitude))
                        .title("${event.type.displayName} ends")
                        .snippet(detail)
                        .icon(mapMarkerIcons.trafficEvent(event.type, "E")),
                )?.let(roadMarkers::add)
        }
        if (event.geometry.size > 1) {
            map
                .addPolyline(
                    PolylineOptions()
                        .addAll(event.geometry.map { LatLng(it.latitude, it.longitude) })
                        .color(colour)
                        .width(dp(6).toFloat())
                        .zIndex(3f),
                ).let(trafficPolylines::add)
        }
    }

    private fun addRoadFacility(
        map: GoogleMap,
        facility: RoadFacility,
    ) {
        map
            .addMarker(
                MarkerOptions()
                    .position(LatLng(facility.coordinate.latitude, facility.coordinate.longitude))
                    .title(facility.title)
                    .snippet(facility.detail)
                    .icon(mapMarkerIcons.facility(facility))
                    .anchor(.5f, .5f),
            )?.let(roadMarkers::add)
    }

    private fun addWeatherWarning(
        map: GoogleMap,
        warning: WeatherWarning,
    ) {
        map
            .addMarker(
                MarkerOptions()
                    .position(LatLng(warning.coordinate.latitude, warning.coordinate.longitude))
                    .title(warning.event)
                    .snippet(
                        listOf(warning.severity, warning.headline, warning.description)
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString(" · "),
                    ).icon(mapMarkerIcons.weatherWarning())
                    .anchor(.5f, .5f)
                    .zIndex(4f),
            )?.let(roadMarkers::add)
    }

    private fun addRoadWeather(
        map: GoogleMap,
        result: RoadWeatherResult,
    ) {
        val forecast = result.mostSevere ?: return
        val label =
            when {
                forecast.condition.severity >= 4 -> "ICE"
                forecast.condition.severity >= 3 -> "SNOW"
                forecast.condition.severity >= 1 -> "WET"
                else -> "DRY"
            }
        val detail =
            buildString {
                append("DWD 24 h road forecast · ${forecast.condition.displayName}")
                forecast.surfaceTemperatureC?.let {
                    append(String.format(Locale.US, " · surface %.1f°C", it))
                }
                forecast.airTemperatureC?.let {
                    append(String.format(Locale.US, " · air %.1f°C", it))
                }
                append(String.format(Locale.US, " · station %.1f km", result.stationDistanceMeters / 1_000.0))
            }
        map
            .addMarker(
                MarkerOptions()
                    .position(LatLng(forecast.coordinate.latitude, forecast.coordinate.longitude))
                    .title("Road weather · $label")
                    .snippet(detail)
                    .icon(mapMarkerIcons.roadWeather(forecast.condition))
                    .anchor(.5f, .5f)
                    .zIndex(4f),
            )?.let(roadMarkers::add)
    }

    private fun roadInfrastructureIcon(point: RoadInfrastructurePoint): BitmapDescriptor = mapMarkerIcons.infrastructure(point)

    private fun selectRoadInfrastructurePoints(
        map: GoogleMap,
        allPoints: List<RoadInfrastructurePoint>,
    ): List<RoadInfrastructurePoint> {
        val zoom = map.cameraPosition.zoom
        val maximum =
            when {
                zoom < 13f -> 44
                zoom < 14f -> 72
                zoom < 15f -> 120
                else -> MAX_ROAD_MARKERS
            }
        val cellSize =
            dp(
                when {
                    zoom < 13f -> 62
                    zoom < 14f -> 52
                    zoom < 15f -> 43
                    else -> 34
                },
            ).coerceAtLeast(1)
        val occupiedCells = mutableSetOf<Pair<Int, Int>>()
        return allPoints
            .filter(RoadInfrastructurePoint::shouldDisplayOnMap)
            .filter { roadSignFilters.isEnabled(DrivingContext.PARKED, it.type) }
            .sortedBy(::roadInfrastructurePriority)
            .asSequence()
            .filter { point ->
                val screen =
                    map.projection.toScreenLocation(
                        LatLng(point.coordinate.latitude, point.coordinate.longitude),
                    )
                occupiedCells.add(screen.x / cellSize to screen.y / cellSize)
            }.take(maximum)
            .toList()
    }

    private fun roadInfrastructurePriority(point: RoadInfrastructurePoint): Int =
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

    private fun trafficEventColour(type: TrafficEventType): Int =
        when (type) {
            TrafficEventType.QUEUE -> Color.rgb(229, 57, 53)
            TrafficEventType.WARNING -> Color.rgb(251, 140, 0)
            TrafficEventType.ROADWORK -> Color.rgb(245, 124, 0)
            TrafficEventType.CLOSURE -> Color.rgb(123, 31, 162)
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

    private fun clearRoadContext() {
        roadMarkers.forEach(Marker::remove)
        roadMarkers.clear()
        trafficPolylines.forEach(Polyline::remove)
        trafficPolylines.clear()
    }

    private fun initialRoadStatus(): String =
        if (anyRoadLayerEnabled()) {
            "Road signs + live traffic loading…"
        } else {
            "Road context layer off"
        }

    private fun showRouteStopModeDialog(supermarket: Boolean) {
        val current =
            routeStopPreferences.load().let {
                if (supermarket) it.supermarketMode else it.fuelMode
            }
        val title = if (supermarket) "Supermarket stop" else "Fuel stop"
        val options =
            arrayOf(
                "Off",
                "Need now — first suitable open place ahead",
                "Best detour — smallest added distance later on the route",
            )
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, current.ordinal, null)
                .setNegativeButton("Cancel", null)
                .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val mode = RouteStopMode.entries[position]
                if (supermarket) {
                    routeStopPreferences.setSupermarketMode(mode)
                } else {
                    routeStopPreferences.setFuelMode(mode)
                }
                supermarketStopButton.text = supermarketStopButtonText()
                fuelStopButton.text = fuelStopButtonText()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun supermarketStopButtonText(): String = "🛒 Supermarket · ${routeStopPreferences.load().supermarketMode.shortLabel}"

    private fun fuelStopButtonText(): String = "⛽ Fuel · ${routeStopPreferences.load().fuelMode.shortLabel}"

    private fun routeStyleButtonText(): String {
        val style = routeStylePreferences.load()
        val detail = if (style == RouteStyle.SCENIC) " (avoids highways)" else ""
        return "Route: ${style.label}$detail"
    }

    private fun formatRelativeTime(timestampMillis: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )

    private fun formatStorageSize(bytes: Long): String =
        when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun makeButton(
        text: String,
        primary: Boolean,
        iconRes: Int? = null,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else textMutedColor)
        backgroundTintList = ColorStateList.valueOf(if (primary) primaryColor else panelControlColor)
        setOnClickListener { onClick() }
        minHeight = dp(48)
        stateListAnimator = null
        iconRes?.let { res ->
            val icon =
                ContextCompat.getDrawable(this@MainActivity, res)?.mutate()?.apply {
                    setTint(if (primary) Color.WHITE else textMutedColor)
                }
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }

    private fun compactButton(
        text: String,
        iconRes: Int? = null,
        onClick: () -> Unit,
    ) = makeButton(text, primary = false, iconRes = iconRes, onClick = onClick).apply {
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun roundedPanel(
        color: Int,
        radiusDp: Int,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(panelTopColor, color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), borderGlowColor)
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
    ) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        setLineSpacing(0f, 1.12f)
    }

    private fun matchWidth(top: Int = 0) =
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(top) }

    private fun weightedControl(startMargin: Int = 0) =
        LinearLayout
            .LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).also { it.marginStart = dp(startMargin) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val LOCATION_MAX_AGE_MILLIS = 5 * 60_000L
        private const val MOVEMENT_MAX_AGE_MILLIS = 2 * 60_000L
        private const val MOVING_SPEED_KPH = 10f
        private const val MAX_CAMERA_MARKERS = 60
        private const val CAMERA_RADIUS_METERS = 30_000
        private const val CAMERA_FRAME_RADIUS_METERS = 8_000
        private const val MIN_CAMERA_LAYER_ZOOM = 8.5f
        private const val MIN_OPENSTREETMAP_QUERY_ZOOM = 10.5f
        private const val CAMERA_GROUP_THRESHOLD = 16
        private const val CAMERA_GROUP_MAX_ZOOM = 14.5f
        private const val CAMERA_GROUP_CELL_DP = 48
        private const val MIN_ROAD_CONTEXT_ZOOM = 12f
        private const val MAX_ROAD_MARKERS = 240
        private const val MAX_FACILITY_MARKERS = 80
        private const val MAX_WEATHER_WARNING_MARKERS = 20
        private const val MAX_SPEED_LIMIT_ROAD_SECTIONS = 450
        private const val CLUSTER_ZOOM_STEP = 2.5f
        private const val MAX_MAP_ZOOM = 19f
        private const val PHONE_MAP_TAG = "roadpulse_phone_map"
        private val DEFAULT_MAP_CENTER = LatLng(51.1657, 10.4515)
        private val ROAD_LAYER_BUNDLE =
            listOf(
                DisplayLayer.ROAD_SIGNS,
                DisplayLayer.AUTOBAHN_TRAFFIC,
                DisplayLayer.AUTOBAHN_FACILITIES,
                DisplayLayer.WEATHER,
            )
    }

    // Centralized in res/values/colors.xml so the palette has one source of truth;
    // resolved lazily since Activity resources aren't safe to read before attach.
    private val backgroundColor by lazy { ContextCompat.getColor(this, R.color.rp_background) }
    private val panelColor by lazy { ContextCompat.getColor(this, R.color.rp_panel) }
    private val panelTopColor by lazy { ContextCompat.getColor(this, R.color.rp_panel_top) }
    private val panelControlColor by lazy { ContextCompat.getColor(this, R.color.rp_panel_control) }
    private val primaryColor by lazy { ContextCompat.getColor(this, R.color.rp_primary) }
    private val accentColor by lazy { ContextCompat.getColor(this, R.color.rp_accent) }
    private val cameraAccentColor by lazy { ContextCompat.getColor(this, R.color.rp_camera_accent) }
    private val roadAccentColor by lazy { ContextCompat.getColor(this, R.color.rp_road_accent) }
    private val textMutedColor by lazy { ContextCompat.getColor(this, R.color.rp_text_muted) }
    private val borderGlowColor by lazy { ContextCompat.getColor(this, R.color.rp_border_glow) }

    private data class CameraLayerResult(
        val cameras: List<NearbyOpenGatsoPoi>,
        val openStreetMapChecked: Boolean,
        val openStreetMapUsedSavedData: Boolean,
        val latestTimestampMillis: Long,
    )

    private data class RoadContextResult(
        val infrastructure: RoadInfrastructureResult,
        val traffic: TrafficEventResult,
        val facilities: RoadFacilityResult,
        val weather: RoadWeatherResult?,
        val warnings: WeatherWarningResult,
        val mappedRoadDataUnavailable: Boolean,
        val liveTrafficUnavailable: Boolean,
    )
}
