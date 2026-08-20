package com.navplus.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val SHOW_SPEED_CAMERAS    = booleanPreferencesKey("show_speed_cameras")
        private val SHOW_SPEED_LIMIT      = booleanPreferencesKey("show_speed_limit")
        private val SHOW_SIGNBOARDS       = booleanPreferencesKey("show_signboards")
        private val SHOW_LANE_GUIDANCE    = booleanPreferencesKey("show_lane_guidance")
        private val SHOW_BORDER_ALERTS    = booleanPreferencesKey("show_border_alerts")
        private val SHOW_ROAD_PERSONALITY = booleanPreferencesKey("show_road_personality")
        private val AVOID_TOLLS           = booleanPreferencesKey("avoid_tolls")
        private val AVOID_HIGHWAYS        = booleanPreferencesKey("avoid_highways")
        private val AVOID_FERRIES         = booleanPreferencesKey("avoid_ferries")
        private val NAV_MAP_TILT          = booleanPreferencesKey("nav_map_tilt")
        private val KEEP_SCREEN_ON        = booleanPreferencesKey("keep_screen_on")
        private val DISTANCE_UNITS        = stringPreferencesKey("distance_units")
        private val VEHICLE_TYPE          = stringPreferencesKey("vehicle_type")
    }

    val settings: Flow<UserSettings> = dataStore.data.map { p ->
        UserSettings(
            vehicleType         = p[VEHICLE_TYPE]?.let { runCatching { VehicleType.valueOf(it) }.getOrNull() }
                                      ?: VehicleType.ARROW,
            showSpeedCameras    = p[SHOW_SPEED_CAMERAS]    ?: true,
            showSpeedLimit      = p[SHOW_SPEED_LIMIT]      ?: true,
            showSignboards      = p[SHOW_SIGNBOARDS]       ?: true,
            showLaneGuidance    = p[SHOW_LANE_GUIDANCE]    ?: true,
            showBorderAlerts    = p[SHOW_BORDER_ALERTS]    ?: true,
            showRoadPersonality = p[SHOW_ROAD_PERSONALITY] ?: true,
            avoidTolls          = p[AVOID_TOLLS]           ?: false,
            avoidHighways       = p[AVOID_HIGHWAYS]        ?: false,
            avoidFerries        = p[AVOID_FERRIES]         ?: false,
            navMapTilt          = p[NAV_MAP_TILT]          ?: true,
            keepScreenOn        = p[KEEP_SCREEN_ON]        ?: true,
            units               = p[DISTANCE_UNITS]?.let { runCatching { DistanceUnits.valueOf(it) }.getOrNull() }
                                      ?: DistanceUnits.METRIC,
        )
    }

    suspend fun setShowSpeedCameras(v: Boolean)    { dataStore.edit { it[SHOW_SPEED_CAMERAS]    = v } }
    suspend fun setShowSpeedLimit(v: Boolean)      { dataStore.edit { it[SHOW_SPEED_LIMIT]      = v } }
    suspend fun setShowSignboards(v: Boolean)      { dataStore.edit { it[SHOW_SIGNBOARDS]       = v } }
    suspend fun setShowLaneGuidance(v: Boolean)    { dataStore.edit { it[SHOW_LANE_GUIDANCE]    = v } }
    suspend fun setShowBorderAlerts(v: Boolean)    { dataStore.edit { it[SHOW_BORDER_ALERTS]    = v } }
    suspend fun setShowRoadPersonality(v: Boolean) { dataStore.edit { it[SHOW_ROAD_PERSONALITY] = v } }
    suspend fun setAvoidTolls(v: Boolean)          { dataStore.edit { it[AVOID_TOLLS]           = v } }
    suspend fun setAvoidHighways(v: Boolean)       { dataStore.edit { it[AVOID_HIGHWAYS]        = v } }
    suspend fun setAvoidFerries(v: Boolean)        { dataStore.edit { it[AVOID_FERRIES]         = v } }
    suspend fun setNavMapTilt(v: Boolean)          { dataStore.edit { it[NAV_MAP_TILT]          = v } }
    suspend fun setKeepScreenOn(v: Boolean)        { dataStore.edit { it[KEEP_SCREEN_ON]        = v } }
    suspend fun setUnits(v: DistanceUnits)         { dataStore.edit { it[DISTANCE_UNITS]        = v.name } }
    suspend fun setVehicleType(v: VehicleType)     { dataStore.edit { it[VEHICLE_TYPE]          = v.name } }
}
