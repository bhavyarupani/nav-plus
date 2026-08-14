package com.roadpulse.auto.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Fastest uses Google's default routing. Scenic sets the Navigation SDK's own
 * `avoidHighways` option, so the resulting route still gets full Google turn-by-turn
 * guidance — unlike a third-party "scenic" route, which Google can't actually drive.
 */
enum class RouteStyle {
    FASTEST,
    SCENIC,
    ;

    fun next(): RouteStyle =
        when (this) {
            FASTEST -> SCENIC
            SCENIC -> FASTEST
        }

    val label: String
        get() =
            when (this) {
                FASTEST -> "Fastest"
                SCENIC -> "Scenic"
            }
}

class RouteStylePreferencesStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): RouteStyle {
        val stored = preferences.getString(KEY, null) ?: return RouteStyle.FASTEST
        return runCatching { RouteStyle.valueOf(stored) }.getOrDefault(RouteStyle.FASTEST)
    }

    fun set(style: RouteStyle) {
        preferences.edit { putString(KEY, style.name) }
    }

    companion object {
        private const val PREFERENCES_NAME = "route_style_preferences"
        private const val KEY = "route_style"
    }
}
