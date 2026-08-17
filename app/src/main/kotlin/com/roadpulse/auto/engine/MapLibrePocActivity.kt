package com.roadpulse.auto.engine

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.roadpulse.auto.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.CompletableFuture

/**
 * Free-stack migration proof of concept: MapLibre Native rendering a real, self-generated vector
 * tile package (Bremen, from a genuine Geofabrik extract processed with Planetiler - see
 * ZERO_COST_ARCHITECTURE.md) over a loopback-only local tile server, with an original style. Not
 * a hosted tile service, not a Google/Mapbox/MapTiler style. Not linked from any app screen -
 * launch directly for verification:
 * `adb shell am start -n com.roadpulse.auto/.engine.MapLibrePocActivity`
 */
class MapLibrePocActivity : Activity() {
    private lateinit var mapView: MapView
    private var tileServer: LocalMbtilesServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        super.onCreate(savedInstanceState)
        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)

        CompletableFuture
            .supplyAsync {
                LocalMbtilesServer(applicationContext, "bremen.mbtiles").apply { start() }
            }.thenAccept { server ->
                tileServer = server
                runOnUiThread { loadMap(server.port) }
            }
    }

    private fun loadMap(port: Int) {
        val styleJson =
            resources
                .openRawResource(R.raw.maplibre_poc_style)
                .bufferedReader()
                .use { it.readText() }
                .replace("__PORT__", port.toString())
        mapView.getMapAsync { map ->
            // Diagnosed via instrumented server-side logging: MapLibre's tile prefetch issues the
            // real viewport tiles AND coarser placeholder tiles together, then cancels the real
            // ones in favor of the placeholders almost immediately - confirmed the server was
            // reading and answering the exact cancelled coordinates within single-digit ms, so
            // this isn't a server timing issue. Disabling prefetch makes MapLibre request only
            // the tiles it actually intends to render.
            map.setPrefetchesTiles(false)
            // Bremen: the region this proof-of-concept package actually covers.
            map.cameraPosition =
                CameraPosition
                    .Builder()
                    .target(LatLng(53.0793, 8.8017))
                    .zoom(11.0)
                    .build()
            map.setStyle(Style.Builder().fromJson(styleJson)) {
                Toast.makeText(this, "Rendering self-generated Bremen tiles (port $port)", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
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
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        tileServer?.stop()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
