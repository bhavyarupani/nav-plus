package com.roadpulse.auto.engine

import android.app.Activity
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Free-stack migration proof of concept: proves MapLibre Native actually initializes and renders
 * on a real device in this project's real Gradle/AGP/Kotlin setup, independent of any tile
 * service. See ZERO_COST_ARCHITECTURE.md. Not linked from any app screen - launch directly for
 * verification:
 * `adb shell am start -n com.roadpulse.auto/.engine.MapLibrePocActivity`
 *
 * Deliberately uses an inline style with no tile source, so this proves the rendering pipeline
 * itself rather than depending on any external service - the real tile pipeline (Geofabrik +
 * Planetiler, self-generated) is separate, larger work tracked in ZERO_COST_ARCHITECTURE.md.
 */
class MapLibrePocActivity : Activity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(this)
        super.onCreate(savedInstanceState)
        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.setStyle(
                Style.Builder().fromJson(MINIMAL_SMOKE_TEST_STYLE_JSON),
            )
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
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private companion object {
        // No "sources" at all - a background-only style, so this smoke test has zero network
        // dependency and proves nothing beyond "does the renderer draw a frame."
        const val MINIMAL_SMOKE_TEST_STYLE_JSON = """
            {
              "version": 8,
              "name": "roadpulse-smoke-test",
              "sources": {},
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#0B1220" }
                }
              ]
            }
        """
    }
}
