package com.navplus.core.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.distanceTo
import com.navplus.core.settings.VehicleType
import android.graphics.Color as AndroidColor
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID       = "navplus-route"
private const val ROUTE_CASING_ID       = "navplus-route-casing"
private const val ROUTE_LINE_ID         = "navplus-route-line"
private const val ROUTE_ALTS_SOURCE_ID  = "navplus-route-alternatives"
private const val ROUTE_ALT_CASING_ID   = "navplus-route-alt-casing"
private const val ROUTE_ALT_LINE_ID     = "navplus-route-alt-line"
private const val ROUTE_SELECTED_CASING_ID = "navplus-route-selected-casing"
private const val ROUTE_SELECTED_LINE_ID = "navplus-route-selected-line"
private const val TRAFFIC_SOURCE_ID     = "navplus-traffic-flow"
private const val TRAFFIC_LAYER_ID      = "navplus-traffic-flow-layer"
private const val CAMERA_SOURCE_ID      = "navplus-cameras"
private const val CAMERA_CIRCLE_ID      = "navplus-cameras-circle"
private const val CAMERA_REDLIGHT_TOP_ID = "navplus-cameras-redlight-top"
private const val CAMERA_REDLIGHT_MID_ID = "navplus-cameras-redlight-mid"
private const val CAMERA_REDLIGHT_BOTTOM_ID = "navplus-cameras-redlight-bottom"
private const val CAMERA_TEXT_ID        = "navplus-cameras-text"
private const val VEHICLE_SOURCE_ID     = "navplus-user-vehicle"
private const val VEHICLE_LAYER_ID      = "navplus-user-vehicle-layer"
private const val VEHICLE_IMAGE_ID      = "navplus-user-vehicle-image"

// Marker glide. The span is measured from the gap between the last two fixes, so
// the marker keeps moving for exactly as long as it takes the next one to arrive.
private const val GLIDE_DEFAULT_NANOS = 1_000_000_000L  // before a gap can be measured
private const val GLIDE_MIN_NANOS     =   200_000_000L
private const val GLIDE_MAX_NANOS     = 2_000_000_000L
private const val GLIDE_SNAP_METERS   = 150.0           // beyond this, cut instead of glide

@SuppressLint("MissingPermission")
@Composable
fun NavMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    cameraPosition: LatLng,
    zoom: Double = 15.0,
    bearing: Float = 0f,
    tilt: Double = 0.0,
    showLocationIndicator: Boolean = true,
    trackCamera: Boolean = false,
    recenterTrigger: Int = 0,
    routeGeometry: List<LatLng>? = null,
    routeAlternatives: List<MapRouteLine> = emptyList(),
    selectedRouteId: String? = null,
    cameras: List<CameraMarker> = emptyList(),
    trafficFlowTileUrls: List<String> = emptyList(),
    onCameraIdle: ((minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) -> Unit)? = null,
    onCameraTap: ((CameraMarker) -> Unit)? = null,
    onRouteTap: ((String) -> Unit)? = null,
    onMapReady: (MapLibreMap) -> Unit = {},
    // Vehicle icon — when provided, shows the user's vehicle instead of the default blue dot.
    vehicleType: VehicleType? = null,
    userPosition: LatLng? = null,
    userBearing: Float = 0f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = rememberMapView(context)
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    // Increments each time the style finishes loading so LaunchedEffects re-run after style reload.
    var styleTick by remember { mutableIntStateOf(0) }
    var resumeSignal by remember { mutableIntStateOf(0) }

    val currentOnCameraIdle = rememberUpdatedState(onCameraIdle)
    val currentOnCameraTap = rememberUpdatedState(onCameraTap)
    val currentOnRouteTap = rememberUpdatedState(onRouteTap)
    val currentCameras = rememberUpdatedState(cameras)

    // Read by the glide loop each frame, so a new fix redirects the animation
    // in flight rather than restarting the coroutine.
    val glidePosition = rememberUpdatedState(userPosition)
    val glideBearing  = rememberUpdatedState(userBearing)
    val glideZoom     = rememberUpdatedState(zoom)
    val glideTilt     = rememberUpdatedState(tilt)

    // When the camera follows the vehicle, the glide loop drives the camera too.
    // Otherwise the viewport would jerk once per fix while the marker glided
    // inside it, and the marker would appear to slide backwards.
    // Requires a fix: with none, the glide loop has nothing to aim at, and the
    // effect below still needs to place the camera.
    val cameraFollowsGlide = trackCamera && vehicleType != null && userPosition != null

    AndroidView(factory = { mapView }, modifier = modifier)

    // Lifecycle + style loading.
    DisposableEffect(lifecycle, styleUrl) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> {
                    mapView.onResume()
                    resumeSignal++
                }
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else                       -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.setStyle(styleUrl) { style ->
                // Show the default blue dot only when no custom vehicle icon is configured.
                if (showLocationIndicator && vehicleType == null && context.hasLocationPermission()) {
                    val lc = map.locationComponent
                    val opts = LocationComponentActivationOptions
                        .builder(context, style)
                        .useDefaultLocationEngine(true)
                        .build()
                    lc.activateLocationComponent(opts)
                    lc.isLocationComponentEnabled = true
                    lc.cameraMode = CameraMode.NONE
                    lc.renderMode = RenderMode.COMPASS
                }
                mapRef = map
                styleTick++
                onMapReady(map)
            }
        }
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Camera moves — instant (moveCamera) when tracking GPS, animated otherwise.
    // Skipped entirely while the glide loop owns the camera.
    // recenterTrigger is included so an explicit re-center fires even when cameraPosition
    // hasn't changed value (e.g. user stationary, pans away, taps My Location).
    DisposableEffect(cameraPosition, zoom, bearing, tilt, cameraFollowsGlide, recenterTrigger) {
        if (!cameraFollowsGlide) {
            mapView.getMapAsync { map ->
                val pos = CameraPosition.Builder()
                    .target(MapLatLng(cameraPosition.lat, cameraPosition.lng))
                    .zoom(zoom)
                    .bearing(bearing.toDouble())
                    .tilt(tilt)
                    .build()
                if (trackCamera) map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                else map.animateCamera(CameraUpdateFactory.newCameraPosition(pos))
            }
        }
        onDispose {}
    }

    // Route polyline — redraws when map or geometry changes, and after style reload.
    LaunchedEffect(mapRef, styleTick, routeGeometry, routeAlternatives, selectedRouteId) {
        val map = mapRef ?: return@LaunchedEffect
        updateRouteLayer(map, if (routeAlternatives.isEmpty()) routeGeometry else null)
        updateRouteAlternativesLayer(map, routeAlternatives, selectedRouteId)
    }

    // Live traffic flow raster tiles. This is separate from the base map style so toggles
    // can enable/disable it without replacing the whole style or route state.
    LaunchedEffect(mapRef, styleTick, trafficFlowTileUrls) {
        val map = mapRef ?: return@LaunchedEffect
        updateTrafficLayer(map, trafficFlowTileUrls)
    }

    // Camera markers — redraws when map, markers, or style tick changes.
    LaunchedEffect(mapRef, styleTick, cameras) {
        val map = mapRef ?: return@LaunchedEffect
        updateCameraLayer(map, cameras)
    }

    // Optional marker tap handling. Used on the normal map for camera source/debug details;
    // navigation deliberately does not pass a callback, so active driving stays uncluttered.
    DisposableEffect(mapRef, density) {
        val map = mapRef ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { click ->
            val callback = currentOnCameraTap.value ?: return@OnMapClickListener false
            val camera = cameraAtClick(
                map = map,
                click = click,
                cameras = currentCameras.value,
                density = density,
            ) ?: return@OnMapClickListener false
            callback(camera)
            true
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    // Route line tap handling for preview mode. The callback is intentionally optional so
    // active driving can leave route interactions disabled.
    DisposableEffect(mapRef) {
        val map = mapRef ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { click ->
            val callback = currentOnRouteTap.value ?: return@OnMapClickListener false
            val point = map.projection.toScreenLocation(click)
            val features = map.queryRenderedFeatures(
                point,
                ROUTE_SELECTED_LINE_ID,
                ROUTE_SELECTED_CASING_ID,
                ROUTE_ALT_LINE_ID,
                ROUTE_ALT_CASING_ID,
            )
            val routeId = features.firstOrNull()?.getStringProperty("routeId")
                ?: return@OnMapClickListener false
            callback(routeId)
            true
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    // Register camera idle listener once when map is ready; fires current viewport immediately.
    LaunchedEffect(mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        val listener = MapLibreMap.OnCameraIdleListener {
            fireViewport(map, currentOnCameraIdle.value)
        }
        map.addOnCameraIdleListener(listener)
        fireViewport(map, currentOnCameraIdle.value)
    }

    // Re-draw layers and re-fire viewport when app returns to foreground.
    LaunchedEffect(mapRef, resumeSignal) {
        val map = mapRef ?: return@LaunchedEffect
        if (resumeSignal == 0) return@LaunchedEffect
        updateCameraLayer(map, cameras)
        updateRouteLayer(map, if (routeAlternatives.isEmpty()) routeGeometry else null)
        updateRouteAlternativesLayer(map, routeAlternatives, selectedRouteId)
        if (vehicleType != null) registerVehicleIcon(map, context, vehicleType, density)
        fireViewport(map, currentOnCameraIdle.value)
    }

    // Register vehicle icon bitmap whenever the style reloads or the vehicle type changes.
    LaunchedEffect(mapRef, styleTick, vehicleType) {
        val map = mapRef ?: return@LaunchedEffect
        val type = vehicleType ?: return@LaunchedEffect
        registerVehicleIcon(map, context, type, density)
    }

    // Glide the vehicle marker between GPS fixes. Runs for as long as the map and
    // vehicle type are unchanged; new fixes are picked up through the state above.
    LaunchedEffect(mapRef, styleTick, vehicleType, cameraFollowsGlide) {
        val map = mapRef ?: return@LaunchedEffect
        if (vehicleType == null) {
            removeVehicleLayer(map)
            return@LaunchedEffect
        }
        glideVehicle(
            map = map,
            position = glidePosition,
            bearing = glideBearing,
            zoom = glideZoom,
            tilt = glideTilt,
            driveCamera = cameraFollowsGlide,
        )
    }
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Moves the vehicle marker smoothly between GPS fixes.
 *
 * Fixes land roughly once a second; writing each one straight to the source makes
 * the marker teleport. This interpolates position and heading across the measured
 * gap instead, one step per frame, restarting from wherever the marker currently
 * is so a fix landing mid-glide redirects rather than snaps.
 *
 * When [driveCamera] is set the camera moves on the same interpolated values —
 * including the vehicle's heading, which is the heading-up navigation case. The
 * marker then holds still on screen and the map slides underneath it.
 *
 * Suspends forever; cancelled when the calling effect restarts.
 */
private suspend fun glideVehicle(
    map: MapLibreMap,
    position: State<LatLng?>,
    bearing: State<Float>,
    zoom: State<Double>,
    tilt: State<Double>,
    driveCamera: Boolean,
) {
    var source: GeoJsonSource? = null
    var rendered: LatLng? = null
    var renderedBearing = 0f
    var lastFixNanos = 0L

    // collectLatest cancels the animation in flight when a fix lands early, and
    // suspends between fixes — a parked vehicle stops requesting frames entirely.
    snapshotFlow { GlideFix(position.value, bearing.value, zoom.value, tilt.value) }
        .collectLatest { fix ->
            val target = fix.position
            if (target == null) {
                if (rendered != null) {
                    removeVehicleLayer(map)
                    source = null
                    rendered = null
                }
                return@collectLatest
            }

            // A jump this large is a re-acquired fix or a teleport, not driving —
            // crawling across it would look worse than cutting to it.
            val origin = rendered?.takeIf { it.distanceTo(target) <= GLIDE_SNAP_METERS }
            val originPos = origin ?: target
            val originBearing = if (origin != null) renderedBearing else fix.bearing

            var startNanos = -1L
            var spanNanos = GLIDE_DEFAULT_NANOS

            while (true) {
                val now = withFrameNanos { it }
                if (startNanos < 0L) {
                    startNanos = now
                    spanNanos = if (lastFixNanos == 0L) GLIDE_DEFAULT_NANOS
                                else (now - lastFixNanos).coerceIn(GLIDE_MIN_NANOS, GLIDE_MAX_NANOS)
                    lastFixNanos = now
                }
                val t = if (spanNanos <= 0L) 1f
                        else ((now - startNanos).toFloat() / spanNanos).coerceIn(0f, 1f)

                val pos = lerpLatLng(originPos, target, t)
                val heading = lerpBearing(originBearing, fix.bearing, t)
                rendered = pos
                renderedBearing = heading

                if (source == null) source = ensureVehicleLayer(map)
                source?.setGeoJson(vehicleFeature(pos, heading))

                if (driveCamera) {
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(MapLatLng(pos.lat, pos.lng))
                                .zoom(fix.zoom)
                                .bearing(heading.toDouble())
                                .tilt(fix.tilt)
                                .build()
                        )
                    )
                }

                if (t >= 1f) break
            }
        }
}

/** One GPS fix plus the camera settings to apply with it. */
private data class GlideFix(
    val position: LatLng?,
    val bearing: Float,
    val zoom: Double,
    val tilt: Double,
)

private fun lerpLatLng(from: LatLng, to: LatLng, t: Float): LatLng = LatLng(
    from.lat + (to.lat - from.lat) * t,
    from.lng + (to.lng - from.lng) * t,
)

/** Interpolates the short way around, so 350° → 10° turns +20° rather than -340°. */
private fun lerpBearing(from: Float, to: Float, t: Float): Float {
    val delta = ((to - from) % 360f + 540f) % 360f - 180f
    return ((from + delta * t) % 360f + 360f) % 360f
}

private fun updateRouteLayer(map: MapLibreMap, geometry: List<LatLng>?) {
    val style = map.style ?: return
    if (style.getLayer(ROUTE_LINE_ID) != null) style.removeLayer(ROUTE_LINE_ID)
    if (style.getLayer(ROUTE_CASING_ID) != null) style.removeLayer(ROUTE_CASING_ID)
    if (style.getSource(ROUTE_SOURCE_ID) != null) style.removeSource(ROUTE_SOURCE_ID)
    if (geometry.isNullOrEmpty()) return

    val coords = geometry.joinToString(",") { "[${it.lng},${it.lat}]" }
    val geoJson = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geoJson))
    style.addLayer(LineLayer(ROUTE_CASING_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor("#FFFFFF"),
        PropertyFactory.lineWidth(12f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(0.6f),
    ))
    style.addLayer(LineLayer(ROUTE_LINE_ID, ROUTE_SOURCE_ID).withProperties(
        PropertyFactory.lineColor("#3B82F6"),
        PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(0.92f),
    ))
}

private fun updateRouteAlternativesLayer(
    map: MapLibreMap,
    routes: List<MapRouteLine>,
    selectedRouteId: String?,
) {
    val style = map.style ?: return
    listOf(
        ROUTE_SELECTED_LINE_ID,
        ROUTE_SELECTED_CASING_ID,
        ROUTE_ALT_LINE_ID,
        ROUTE_ALT_CASING_ID,
    ).forEach { id ->
        if (style.getLayer(id) != null) style.removeLayer(id)
    }
    if (style.getSource(ROUTE_ALTS_SOURCE_ID) != null) style.removeSource(ROUTE_ALTS_SOURCE_ID)
    if (routes.isEmpty()) return

    val selectedId = selectedRouteId ?: routes.first().id
    val features = routes
        .filter { it.geometry.size >= 2 }
        .joinToString(",") { route ->
            val coords = route.geometry.joinToString(",") { "[${it.lng},${it.lat}]" }
            val selected = if (route.id == selectedId) "true" else "false"
            """{"type":"Feature","properties":{"routeId":"${route.id}","selected":"$selected"},""" +
                """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        }
    if (features.isBlank()) return

    val geoJson = """{"type":"FeatureCollection","features":[$features]}"""
    try {
        style.addSource(GeoJsonSource(ROUTE_ALTS_SOURCE_ID, geoJson))
        style.addLayer(LineLayer(ROUTE_ALT_CASING_ID, ROUTE_ALTS_SOURCE_ID).withFilter(
            Expression.eq(Expression.get("selected"), "false")
        ).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.45f),
        ))
        style.addLayer(LineLayer(ROUTE_ALT_LINE_ID, ROUTE_ALTS_SOURCE_ID).withFilter(
            Expression.eq(Expression.get("selected"), "false")
        ).withProperties(
            PropertyFactory.lineColor("#64748B"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.72f),
        ))
        style.addLayer(LineLayer(ROUTE_SELECTED_CASING_ID, ROUTE_ALTS_SOURCE_ID).withFilter(
            Expression.eq(Expression.get("selected"), "true")
        ).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(13f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.68f),
        ))
        style.addLayer(LineLayer(ROUTE_SELECTED_LINE_ID, ROUTE_ALTS_SOURCE_ID).withFilter(
            Expression.eq(Expression.get("selected"), "true")
        ).withProperties(
            PropertyFactory.lineColor("#2563EB"),
            PropertyFactory.lineWidth(8.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.95f),
        ))
    } catch (e: Exception) {
        Log.e("NavMapView", "add route alternatives failed: $e")
    }
}

private fun updateTrafficLayer(map: MapLibreMap, tileUrls: List<String>) {
    val style = map.style ?: return
    try {
        if (style.getLayer(TRAFFIC_LAYER_ID) != null) style.removeLayer(TRAFFIC_LAYER_ID)
        if (style.getSource(TRAFFIC_SOURCE_ID) != null) style.removeSource(TRAFFIC_SOURCE_ID)
    } catch (e: Exception) {
        Log.e("NavMapView", "remove traffic layer/source failed: $e")
        return
    }
    if (tileUrls.isEmpty()) return

    try {
        val tileSet = TileSet("2.2.0", *tileUrls.toTypedArray()).apply {
            setMinZoom(4f)
            setMaxZoom(22f)
            attribution = "Traffic: TomTom"
        }
        style.addSource(RasterSource(TRAFFIC_SOURCE_ID, tileSet, 256))
        val layer = RasterLayer(TRAFFIC_LAYER_ID, TRAFFIC_SOURCE_ID).withProperties(
            PropertyFactory.rasterOpacity(0.82f),
            PropertyFactory.rasterFadeDuration(120f),
        )
        if (style.getLayer(ROUTE_CASING_ID) != null) {
            style.addLayerBelow(layer, ROUTE_CASING_ID)
        } else {
            style.addLayer(layer)
        }
    } catch (e: Exception) {
        Log.e("NavMapView", "add traffic layer/source failed: $e")
    }
}

private fun cameraColor(typeCode: String): String = when (typeCode) {
    "RED_LIGHT"           -> "#EF4444"
    "COMBINED"            -> "#8B5CF6"
    "AVERAGE_SPEED_START",
    "AVERAGE_SPEED_END",
    "SECTION_CONTROL"     -> "#3B82F6"
    "MOBILE_ZONE"         -> "#F59E0B"
    else                  -> "#EF4444" // FIXED_SPEED default
}

private fun cameraLabel(camera: CameraMarker): String = when (camera.typeCode) {
    "RED_LIGHT" -> ""
    "COMBINED" -> camera.speedLimitKph?.toString() ?: "⋮"
    "AVERAGE_SPEED_START",
    "AVERAGE_SPEED_END" -> "↔"
    "SECTION_CONTROL" -> "↔"
    "MOBILE_ZONE" -> "!"
    else -> camera.speedLimitKph?.toString() ?: "•"
}

private fun updateCameraLayer(map: MapLibreMap, cameras: List<CameraMarker>) {
    Log.d("NavMapView", "updateCameraLayer: ${cameras.size} cameras")
    val style = map.style ?: run { Log.w("NavMapView", "style null, skipping"); return }
    try {
        if (style.getLayer(CAMERA_TEXT_ID) != null) style.removeLayer(CAMERA_TEXT_ID)
        if (style.getLayer(CAMERA_REDLIGHT_BOTTOM_ID) != null) style.removeLayer(CAMERA_REDLIGHT_BOTTOM_ID)
        if (style.getLayer(CAMERA_REDLIGHT_MID_ID) != null) style.removeLayer(CAMERA_REDLIGHT_MID_ID)
        if (style.getLayer(CAMERA_REDLIGHT_TOP_ID) != null) style.removeLayer(CAMERA_REDLIGHT_TOP_ID)
        if (style.getLayer(CAMERA_CIRCLE_ID) != null) style.removeLayer(CAMERA_CIRCLE_ID)
        if (style.getSource(CAMERA_SOURCE_ID) != null) style.removeSource(CAMERA_SOURCE_ID)
    } catch (e: Exception) {
        Log.e("NavMapView", "remove layers/source failed: $e")
        return
    }
    if (cameras.isEmpty()) return

    // Embed color as a hex property in each GeoJSON feature so we can use
    // Expression.toColor(get("color")) — avoids brittle match() expression.
    val features = cameras.joinToString(",") { cam ->
        val label = cameraLabel(cam)
        val color = cameraColor(cam.typeCode)
        """{"type":"Feature","properties":{"label":"$label","color":"$color","type":"${cam.typeCode}"},""" +
            """"geometry":{"type":"Point","coordinates":[${cam.position.lng},${cam.position.lat}]}}"""
    }
    val geoJson = """{"type":"FeatureCollection","features":[$features]}"""
    try {
        style.addSource(GeoJsonSource(CAMERA_SOURCE_ID, geoJson))
        style.addLayer(CircleLayer(CAMERA_CIRCLE_ID, CAMERA_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
            PropertyFactory.circleOpacity(1.0f),
        ))
        style.addLayerAbove(redLightDotLayer(CAMERA_REDLIGHT_TOP_ID, "#FEE2E2", -4f), CAMERA_CIRCLE_ID)
        style.addLayerAbove(redLightDotLayer(CAMERA_REDLIGHT_MID_ID, "#FACC15", 0f), CAMERA_REDLIGHT_TOP_ID)
        style.addLayerAbove(redLightDotLayer(CAMERA_REDLIGHT_BOTTOM_ID, "#22C55E", 4f), CAMERA_REDLIGHT_MID_ID)
        style.addLayerAbove(SymbolLayer(CAMERA_TEXT_ID, CAMERA_SOURCE_ID).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(10f),
            PropertyFactory.textColor(AndroidColor.WHITE),
            PropertyFactory.textHaloColor(AndroidColor.BLACK),
            PropertyFactory.textHaloWidth(0.8f),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textAllowOverlap(true),
        ).apply {
            setMinZoom(12f)
        }, CAMERA_CIRCLE_ID)
    } catch (e: Exception) {
        Log.e("NavMapView", "addLayer/addSource failed: $e")
    }
}

private fun cameraAtClick(
    map: MapLibreMap,
    click: MapLatLng,
    cameras: List<CameraMarker>,
    density: Float,
): CameraMarker? {
    if (cameras.isEmpty()) return null
    val clickPoint = map.projection.toScreenLocation(click)
    val hitRadiusPx = 34f * density
    var bestCamera: CameraMarker? = null
    var bestDistanceSq = hitRadiusPx * hitRadiusPx
    cameras.forEach { camera ->
        val point = map.projection.toScreenLocation(MapLatLng(camera.position.lat, camera.position.lng))
        val dx = point.x - clickPoint.x
        val dy = point.y - clickPoint.y
        val distanceSq = dx * dx + dy * dy
        if (distanceSq <= bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestCamera = camera
        }
    }
    return bestCamera
}

private fun redLightDotLayer(id: String, color: String, translateY: Float): CircleLayer =
    CircleLayer(id, CAMERA_SOURCE_ID)
        .withFilter(Expression.eq(Expression.get("type"), "RED_LIGHT"))
        .withProperties(
            PropertyFactory.circleRadius(1.7f),
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeWidth(0.5f),
            PropertyFactory.circleStrokeColor(AndroidColor.BLACK),
            PropertyFactory.circleTranslate(arrayOf(0f, translateY)),
        ).apply {
            setMinZoom(12f)
        }

private fun fireViewport(
    map: MapLibreMap,
    callback: ((Double, Double, Double, Double) -> Unit)?,
) {
    if (callback == null) return
    val r = map.projection.visibleRegion
    val corners = listOfNotNull(r.nearLeft, r.nearRight, r.farLeft, r.farRight)
    if (corners.size < 2) return
    val minLat = corners.minOf { it.latitude }
    val maxLat = corners.maxOf { it.latitude }
    val minLng = corners.minOf { it.longitude }
    val maxLng = corners.maxOf { it.longitude }
    callback(minLat, maxLat, minLng, maxLng)
}


private fun registerVehicleIcon(
    map: MapLibreMap,
    context: Context,
    vehicleType: VehicleType,
    density: Float,
) {
    val style = map.style ?: return
    try {
        val bitmap = VehicleIconFactory.create(context, vehicleType, density)
        if (style.getImage(VEHICLE_IMAGE_ID) != null) style.removeImage(VEHICLE_IMAGE_ID)
        style.addImage(VEHICLE_IMAGE_ID, bitmap)
    } catch (e: Exception) {
        Log.e("NavMapView", "registerVehicleIcon failed: $e")
    }
}

/** Point feature the vehicle layer renders — rebuilt each frame, so no JSON string. */
private fun vehicleFeature(position: LatLng, bearingDeg: Float): Feature =
    Feature.fromGeometry(Point.fromLngLat(position.lng, position.lat)).apply {
        addNumberProperty("bearing", bearingDeg)
    }

private fun removeVehicleLayer(map: MapLibreMap) {
    val style = map.style ?: return
    try {
        if (style.getLayer(VEHICLE_LAYER_ID) != null) style.removeLayer(VEHICLE_LAYER_ID)
        if (style.getSource(VEHICLE_SOURCE_ID) != null) style.removeSource(VEHICLE_SOURCE_ID)
    } catch (_: Exception) {}
}

/** Returns the vehicle source, creating source + layer on first use. */
private fun ensureVehicleLayer(map: MapLibreMap): GeoJsonSource? {
    val style = map.style ?: return null
    (style.getSource(VEHICLE_SOURCE_ID) as? GeoJsonSource)?.let { return it }
    return try {
        val source = GeoJsonSource(VEHICLE_SOURCE_ID)
        style.addSource(source)
        style.addLayer(
            SymbolLayer(VEHICLE_LAYER_ID, VEHICLE_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(VEHICLE_IMAGE_ID),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                // Lies flat on the road when the navigation camera tilts.
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // Grows with zoom, but nowhere near geographic scale — a true-to-scale
                // car is a few pixels at street zoom. Stays a readable marker throughout.
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(10.0, 0.55f),
                        Expression.stop(14.0, 0.75f),
                        Expression.stop(16.0, 0.90f),
                        Expression.stop(18.0, 1.05f),
                        Expression.stop(20.0, 1.20f),
                    )
                ),
            )
        )
        source
    } catch (e: Exception) {
        Log.e("NavMapView", "ensureVehicleLayer failed: $e")
        null
    }
}

@Composable
private fun rememberMapView(context: Context): MapView {
    return remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
}
