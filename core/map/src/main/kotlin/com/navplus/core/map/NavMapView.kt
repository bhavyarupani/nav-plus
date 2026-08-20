package com.navplus.core.map

import android.content.Context
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID       = "navplus-route"
private const val ROUTE_CASING_ID       = "navplus-route-casing"
private const val ROUTE_LINE_ID         = "navplus-route-line"
private const val CAMERA_SOURCE_ID      = "navplus-cameras"
private const val CAMERA_CIRCLE_ID      = "navplus-cameras-circle"
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
    routeGeometry: List<LatLng>? = null,
    cameras: List<CameraMarker> = emptyList(),
    onCameraIdle: ((minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) -> Unit)? = null,
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
                if (showLocationIndicator && vehicleType == null) {
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
    DisposableEffect(cameraPosition, zoom, bearing, tilt, cameraFollowsGlide) {
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
    LaunchedEffect(mapRef, styleTick, routeGeometry) {
        val map = mapRef ?: return@LaunchedEffect
        updateRouteLayer(map, routeGeometry)
    }

    // Camera markers — redraws when map, markers, or style tick changes.
    LaunchedEffect(mapRef, styleTick, cameras) {
        val map = mapRef ?: return@LaunchedEffect
        updateCameraLayer(map, cameras)
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
        updateRouteLayer(map, routeGeometry)
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

private fun cameraColor(typeCode: String): String = when (typeCode) {
    "RED_LIGHT"           -> "#F97316"
    "COMBINED"            -> "#8B5CF6"
    "AVERAGE_SPEED_START",
    "AVERAGE_SPEED_END",
    "SECTION_CONTROL"     -> "#3B82F6"
    "MOBILE_ZONE"         -> "#F59E0B"
    else                  -> "#EF4444" // FIXED_SPEED default
}

private fun updateCameraLayer(map: MapLibreMap, cameras: List<CameraMarker>) {
    Log.d("NavMapView", "updateCameraLayer: ${cameras.size} cameras")
    val style = map.style ?: run { Log.w("NavMapView", "style null, skipping"); return }
    try {
        if (style.getLayer(CAMERA_TEXT_ID) != null) style.removeLayer(CAMERA_TEXT_ID)
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
        val limitText = cam.speedLimitKph?.toString() ?: ""
        val color = cameraColor(cam.typeCode)
        """{"type":"Feature","properties":{"limit":"$limitText","color":"$color"},""" +
            """"geometry":{"type":"Point","coordinates":[${cam.position.lng},${cam.position.lat}]}}"""
    }
    val geoJson = """{"type":"FeatureCollection","features":[$features]}"""
    try {
        style.addSource(GeoJsonSource(CAMERA_SOURCE_ID, geoJson))
        style.addLayer(CircleLayer(CAMERA_CIRCLE_ID, CAMERA_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
            PropertyFactory.circleOpacity(1.0f),
        ))
        style.addLayerAbove(SymbolLayer(CAMERA_TEXT_ID, CAMERA_SOURCE_ID).withProperties(
            PropertyFactory.textField(Expression.get("limit")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(AndroidColor.WHITE),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textAllowOverlap(true),
        ), CAMERA_CIRCLE_ID)
    } catch (e: Exception) {
        Log.e("NavMapView", "addLayer/addSource failed: $e")
    }
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
