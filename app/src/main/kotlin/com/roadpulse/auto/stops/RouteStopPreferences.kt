package com.roadpulse.auto.stops

import android.content.Context
import androidx.core.content.edit

enum class RouteStopMode {
    OFF,
    NEED_NOW,
    BEST_DETOUR,
    ;

    fun next(): RouteStopMode =
        when (this) {
            OFF -> NEED_NOW
            NEED_NOW -> BEST_DETOUR
            BEST_DETOUR -> OFF
        }

    val shortLabel: String
        get() =
            when (this) {
                OFF -> "off"
                NEED_NOW -> "NOW"
                BEST_DETOUR -> "best"
            }
}

data class RouteStopPreferences(
    val supermarketMode: RouteStopMode,
    val fuelMode: RouteStopMode,
) {
    val hasEnabledStop: Boolean
        get() = supermarketMode != RouteStopMode.OFF || fuelMode != RouteStopMode.OFF
}

class RouteStopPreferencesStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    fun load(): RouteStopPreferences =
        RouteStopPreferences(
            supermarketMode = loadMode(SUPERMARKET_MODE, SUPERMARKET_ENABLED),
            fuelMode = loadMode(FUEL_MODE, FUEL_ENABLED),
        )

    private fun loadMode(
        modeKey: String,
        legacyEnabledKey: String,
    ): RouteStopMode {
        val stored = preferences.getString(modeKey, null)
        if (stored != null) {
            return runCatching { RouteStopMode.valueOf(stored) }.getOrDefault(RouteStopMode.OFF)
        }
        return if (preferences.getBoolean(legacyEnabledKey, false)) {
            RouteStopMode.BEST_DETOUR
        } else {
            RouteStopMode.OFF
        }
    }

    fun setSupermarketMode(mode: RouteStopMode) {
        preferences.edit { putString(SUPERMARKET_MODE, mode.name) }
    }

    fun setFuelMode(mode: RouteStopMode) {
        preferences.edit { putString(FUEL_MODE, mode.name) }
    }

    companion object {
        private const val PREFERENCES_NAME = "route_stop_preferences"
        private const val SUPERMARKET_MODE = "supermarket_mode"
        private const val FUEL_MODE = "fuel_mode"
        private const val SUPERMARKET_ENABLED = "supermarket_enabled"
        private const val FUEL_ENABLED = "fuel_enabled"
    }
}
