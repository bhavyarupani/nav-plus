package com.navplus.core.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navplus.core.common.model.LatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@Composable
fun NavMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    cameraPosition: LatLng,
    zoom: Double = 15.0,
    bearing: Float = 0f,
    tilt: Double = 0.0,
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = rememberMapView(context)

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else                       -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.setStyle(styleUrl) {
                onMapReady(map)
            }
        }
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(cameraPosition, zoom, bearing, tilt) {
        mapView.getMapAsync { map ->
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(MapLatLng(cameraPosition.lat, cameraPosition.lng))
                        .zoom(zoom)
                        .bearing(bearing.toDouble())
                        .tilt(tilt)
                        .build()
                )
            )
        }
        onDispose {}
    }
}

@Composable
private fun rememberMapView(context: Context): MapView {
    return remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
}
