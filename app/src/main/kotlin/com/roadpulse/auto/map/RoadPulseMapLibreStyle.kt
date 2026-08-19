package com.roadpulse.auto.map

import android.content.Context
import android.content.res.Configuration
import com.roadpulse.auto.R

/**
 * Free-stack replacement for the app's former Google-based map styling, which applied Google's
 * proprietary `MapStyleOptions` JSON format (`featureType`/`elementType`/`stylers`) - that format
 * has no mechanical conversion to MapLibre's Mapbox-GL-style-spec JSON (`sources`/`layers`/
 * `paint`), a different schema entirely. `maplibre_style_day.json`/`maplibre_style_night.json`
 * are a from-scratch MapLibre style rewrite, hand-matched to the same day/night color palette as
 * the retired `roadpulse_map_day.json`/`roadpulse_map_night.json` (kept in `res/raw` as the
 * color-mapping reference these were hand-matched against) so the app's visual identity is
 * unchanged by the renderer swap.
 *
 * Deliberately declares no source-level `bounds` either: the local tile server behind `__PORT__`
 * only ever serves whichever single region is currently active, so a static bounds value baked
 * into this shared resource would silently cap every *other* region to no detail past its lowest
 * zoom tiles - confirmed on-device serving Baden-Württemberg through a `bounds` still left over
 * from this app's original Bremen-only build: MapLibre never requested a single z14 tile for it,
 * even though the local server had real data at that zoom (verified by fetching it directly).
 *
 * Deliberately has no label/symbol layers, matching the already-verified-working
 * `maplibre_poc_style.json`: a `symbol` layer with `text-field` but no `glyphs` source stalls
 * MapLibre's entire tile-fetch pipeline for that tile, not just the label layer (root-caused
 * during the MapLibre POC - see ZERO_COST_ARCHITECTURE.md). Labels are reintroduced once a local
 * glyphs PBF source exists, tracked there as follow-up work.
 *
 * Unlike [RoadPulseMapTheme]'s synchronous `GoogleMap.setMapStyle`, applying a MapLibre style is
 * asynchronous (`MapLibreMap.setStyle(Style.Builder, OnStyleLoaded)`), so this only returns the
 * style JSON text (with the local tile server's port substituted in) - callers apply it
 * themselves, exactly as `MapLibrePocActivity.loadMap` already does.
 */
object RoadPulseMapLibreStyle {
    fun styleJson(
        context: Context,
        tileServerPort: Int,
    ): String {
        val nightMode =
            context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        val styleResource =
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                R.raw.maplibre_style_night
            } else {
                R.raw.maplibre_style_day
            }
        return context.resources
            .openRawResource(styleResource)
            .bufferedReader()
            .use { it.readText() }
            .replace("__PORT__", tileServerPort.toString())
    }
}
