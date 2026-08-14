package com.roadpulse.auto.settings

import android.content.Context
import androidx.core.content.edit

enum class DisplayLayer(
    val label: String,
    val description: String,
) {
    SPEED_CAMERAS(
        "Speed cameras",
        "Enforcement locations from Open-GATSO, official government, and OpenStreetMap sources.",
    ),
    ROAD_SIGNS(
        "Road signs & signals",
        "Traffic signals, priority/give-way/stop signs, speed-limit signs, school zones, tunnels, bridges, tolls.",
    ),
    AUTOBAHN_TRAFFIC(
        "Autobahn traffic",
        "Live congestion, closures, and roadworks from Autobahn GmbH.",
    ),
    AUTOBAHN_FACILITIES(
        "Charging, parking & facilities",
        "Autobahn parking, webcams, and restrooms, plus EV chargers from Open Charge Map.",
    ),
    WEATHER(
        "Road weather",
        "DWD road-condition forecasts and official weather warnings.",
    ),
    TERRAIN(
        "Terrain & elevation",
        "Estimated slope ahead on the active route.",
    ),
    SPEED_LIMIT_AHEAD(
        "Speed limit ahead",
        "Upcoming speed-limit changes on the active route.",
    ),
    LANE_GUIDANCE(
        "Lane guidance",
        "Which lane to use for the next manoeuvre.",
    ),
}

enum class DrivingContext { PARKED, DRIVING }

/**
 * Two independent visibility profiles — "parked" (the My Maps home screen) and
 * "driving" (turn-by-turn on the phone and Android Auto) — so a layer can be
 * shown while planning a route but hidden once guidance starts, or vice versa.
 */
class DisplayFilterStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences =
        context.applicationContext
            .getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(
        drivingContext: DrivingContext,
        layer: DisplayLayer,
    ): Boolean {
        val key = key(drivingContext, layer)
        return if (preferences.contains(key)) {
            preferences.getBoolean(key, true)
        } else {
            legacyDefault(drivingContext, layer)
        }
    }

    fun setEnabled(
        drivingContext: DrivingContext,
        layer: DisplayLayer,
        enabled: Boolean,
    ) {
        preferences.edit { putBoolean(key(drivingContext, layer), enabled) }
    }

    // Cameras and road signs while parked used to be governed by two flat booleans on the
    // My Maps home screen; the Autobahn/weather rows rode along with the road-sign toggle.
    // New installs never hit this path since dedicated keys get written on first use.
    private fun legacyDefault(
        drivingContext: DrivingContext,
        layer: DisplayLayer,
    ): Boolean =
        when {
            drivingContext != DrivingContext.PARKED -> true
            layer == DisplayLayer.SPEED_CAMERAS -> legacyPreferences.getBoolean(LEGACY_CAMERA_LAYER_KEY, true)
            layer in ROAD_LAYER_BUNDLE -> legacyPreferences.getBoolean(LEGACY_ROAD_LAYER_KEY, true)
            else -> true
        }

    private fun key(
        drivingContext: DrivingContext,
        layer: DisplayLayer,
    ) = "${drivingContext.name}_${layer.name}"

    companion object {
        private const val PREFERENCES_NAME = "display_filters"
        private const val LEGACY_PREFERENCES_NAME = "MainActivity"
        private const val LEGACY_CAMERA_LAYER_KEY = "camera_layer_enabled"
        private const val LEGACY_ROAD_LAYER_KEY = "road_layer_enabled"
        private val ROAD_LAYER_BUNDLE =
            setOf(
                DisplayLayer.ROAD_SIGNS,
                DisplayLayer.AUTOBAHN_TRAFFIC,
                DisplayLayer.AUTOBAHN_FACILITIES,
                DisplayLayer.WEATHER,
            )
    }
}
