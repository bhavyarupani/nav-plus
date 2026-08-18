package com.roadpulse.auto.map

import android.graphics.Bitmap
import android.graphics.PointF
import com.roadpulse.auto.traffic.RoadCoordinate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

/**
 * Wraps MapLibre's `MapLibreMap`/`SymbolManager`/`LineManager` behind the same shape of
 * operations `MainActivity`, `NavigationActivity`, and `RoadPulseNavigationScreen` already used
 * against `GoogleMap`/`Marker`/`Polyline` - accumulate-in-a-list, clear-and-redraw-on-viewport-
 * change (see ZERO_COST_ARCHITECTURE.md's migration notes on this pattern being uniform across
 * all three screens). Callers keep their own `MutableList<MapMarker>`/`MutableList<MapPolyline>`
 * exactly as they did with Google's `Marker`/`Polyline`, just calling this instead of `GoogleMap`.
 *
 * Uses the `android-plugin-annotation-v9` SymbolManager/LineManager rather than hand-rolling
 * GeoJsonSource/SymbolLayer diffing - it's the direct MapLibre equivalent of
 * `GoogleMap.addMarker`/`addPolyline` (create/delete per-annotation, not a full-source replace
 * per redraw), verified against the real 3.0.2 jar via `javap` before use.
 *
 * Does not use MapLibre's `LocationComponent` for the "my location" puck: the app already reads
 * GPS fixes itself via `android.location.LocationManager` (not Google's FusedLocationProvider,
 * confirmed elsewhere in this codebase), so adopting `LocationComponent`'s separate
 * `LocationEngine` plumbing would just be a second, parallel location pipeline. Instead
 * [setMyLocationPuck] renders the puck as an ordinary managed symbol, fed from the same location
 * updates the rest of the app already consumes.
 */
class MapLibreMapController(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val style: Style,
) {
    private val symbolManager = SymbolManager(mapView, map, style)
    private val lineManager = LineManager(mapView, map, style)
    private val registeredIconIds = mutableSetOf<String>()
    private var myLocationSymbol: Symbol? = null

    fun registerIcon(
        iconId: String,
        bitmap: Bitmap,
    ) {
        if (!registeredIconIds.add(iconId)) return
        style.addImage(iconId, bitmap)
    }

    fun addMarker(
        coordinate: RoadCoordinate,
        iconId: String,
        rotationDegrees: Float = 0f,
    ): MapMarker {
        val symbol =
            symbolManager.create(
                SymbolOptions()
                    .withLatLng(coordinate.toLatLng())
                    .withIconImage(iconId)
                    .withIconRotate(rotationDegrees),
            )
        return MapMarker(symbol)
    }

    fun removeMarker(marker: MapMarker) = symbolManager.delete(marker.symbol)

    fun clearMarkers() = symbolManager.deleteAll()

    fun setOnMarkerClickListener(listener: (MapMarker) -> Boolean) {
        symbolManager.addClickListener { symbol -> listener(MapMarker(symbol)) }
    }

    fun addPolyline(
        points: List<RoadCoordinate>,
        colorHex: String,
        widthDp: Float = 4f,
    ): MapPolyline {
        val line =
            lineManager.create(
                LineOptions()
                    .withLatLngs(points.map { it.toLatLng() })
                    .withLineColor(colorHex)
                    .withLineWidth(widthDp),
            )
        return MapPolyline(line)
    }

    fun removePolyline(polyline: MapPolyline) = lineManager.delete(polyline.line)

    fun clearPolylines() = lineManager.deleteAll()

    fun setMyLocationPuck(
        coordinate: RoadCoordinate,
        bearingDegrees: Float,
        iconId: String,
    ) {
        val existing = myLocationSymbol
        if (existing == null) {
            myLocationSymbol =
                symbolManager.create(
                    SymbolOptions()
                        .withLatLng(coordinate.toLatLng())
                        .withIconImage(iconId)
                        .withIconRotate(bearingDegrees),
                )
        } else {
            existing.latLng = coordinate.toLatLng()
            existing.iconRotate = bearingDegrees
            symbolManager.update(existing)
        }
    }

    fun clearMyLocationPuck() {
        myLocationSymbol?.let(symbolManager::delete)
        myLocationSymbol = null
    }

    fun animateCameraTo(
        coordinate: RoadCoordinate,
        zoom: Double,
    ) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinate.toLatLng(), zoom))
    }

    fun currentZoom(): Double = map.cameraPosition.zoom

    fun cameraTarget(): RoadCoordinate? = map.cameraPosition.target?.toRoadCoordinate()

    /** Pans the camera by a raw screen-pixel delta - for `SurfaceCallback.onScroll` (Android
     * Auto's touchpad/rotary pan gesture). MapLibre's `CameraUpdateFactory` has no direct
     * pixel-scroll update (unlike Google's `CameraUpdateFactory.scrollBy`), so this converts the
     * current camera target to a screen point, offsets it, and converts back. */
    fun scrollBy(
        dxPixels: Float,
        dyPixels: Float,
    ) {
        val target = map.cameraPosition.target ?: return
        val screenPoint = map.projection.toScreenLocation(target)
        val newTarget = map.projection.fromScreenLocation(PointF(screenPoint.x + dxPixels, screenPoint.y + dyPixels))
        map.moveCamera(CameraUpdateFactory.newLatLng(newTarget))
    }

    /** Zooms the camera by a relative delta around a screen focus point - for
     * `SurfaceCallback.onScale` (Android Auto's pinch gesture). */
    fun zoomBy(
        delta: Double,
        focusXPixels: Int,
        focusYPixels: Int,
    ) {
        map.animateCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusXPixels, focusYPixels)))
    }

    fun moveCameraTo(
        coordinate: RoadCoordinate,
        zoom: Double,
    ) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate.toLatLng(), zoom))
    }

    fun animateCameraToBounds(
        coordinates: List<RoadCoordinate>,
        paddingPx: Int,
    ) {
        if (coordinates.isEmpty()) return
        val builder = LatLngBounds.Builder()
        coordinates.forEach { builder.include(it.toLatLng()) }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx))
    }

    fun setOnCameraIdleListener(listener: () -> Unit) {
        map.addOnCameraIdleListener(listener)
    }

    fun setOnMapClickListener(listener: (RoadCoordinate) -> Boolean) {
        map.addOnMapClickListener { latLng -> listener(latLng.toRoadCoordinate()) }
    }

    fun setPadding(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        map.setPadding(left, top, right, bottom)
    }

    /** Current viewport bounds, for the same "is this feature currently visible" filtering the
     * app already does against `GoogleMap.projection.visibleRegion.latLngBounds`. */
    fun visibleBounds(): RoadCoordinateBounds {
        val bounds = map.projection.visibleRegion.latLngBounds
        return RoadCoordinateBounds(
            northEast = RoadCoordinate(bounds.latitudeNorth, bounds.longitudeEast),
            southWest = RoadCoordinate(bounds.latitudeSouth, bounds.longitudeWest),
        )
    }

    fun screenLocation(coordinate: RoadCoordinate): PointF = map.projection.toScreenLocation(coordinate.toLatLng())

    private fun RoadCoordinate.toLatLng() = LatLng(latitude, longitude)

    private fun LatLng.toRoadCoordinate() = RoadCoordinate(latitude, longitude)
}

class MapMarker internal constructor(
    internal val symbol: Symbol,
) {
    val coordinate: RoadCoordinate
        get() = RoadCoordinate(symbol.latLng.latitude, symbol.latLng.longitude)
}

class MapPolyline internal constructor(
    internal val line: Line,
)

data class RoadCoordinateBounds(
    val northEast: RoadCoordinate,
    val southWest: RoadCoordinate,
) {
    fun contains(coordinate: RoadCoordinate): Boolean =
        coordinate.latitude in southWest.latitude..northEast.latitude &&
            coordinate.longitude in southWest.longitude..northEast.longitude
}
