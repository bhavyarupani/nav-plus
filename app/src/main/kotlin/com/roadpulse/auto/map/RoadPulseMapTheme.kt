package com.roadpulse.auto.map

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import com.roadpulse.auto.R

object RoadPulseMapTheme {
    fun apply(
        context: Context,
        map: GoogleMap,
    ): Boolean {
        val nightMode =
            context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        val styleResource =
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                R.raw.roadpulse_map_night
            } else {
                R.raw.roadpulse_map_day
            }
        return runCatching {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, styleResource))
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply RoadPulse map style", error)
        }.getOrDefault(false)
    }

    private const val TAG = "RoadPulseMapTheme"
}
