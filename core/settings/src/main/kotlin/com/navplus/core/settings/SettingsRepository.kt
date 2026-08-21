package com.navplus.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private inline fun <reified T : Enum<T>> enumOf(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        // Vehicle
        private val VEHICLE_TYPE             = stringPreferencesKey("vehicle_type")
        // Route
        private val ROUTE_TYPE               = stringPreferencesKey("route_type")
        private val AVOID_TOLLS              = booleanPreferencesKey("avoid_tolls")
        private val AVOID_HIGHWAYS           = booleanPreferencesKey("avoid_highways")
        private val AVOID_FERRIES            = booleanPreferencesKey("avoid_ferries")
        private val AVOID_UNPAVED            = booleanPreferencesKey("avoid_unpaved")
        private val AVOID_NARROW             = booleanPreferencesKey("avoid_narrow")
        private val AUTO_REROUTE             = booleanPreferencesKey("auto_reroute")
        private val AUTO_ACCEPT_FASTER       = stringPreferencesKey("auto_accept_faster")
        private val ASK_BEFORE_MAJOR_REROUTE = booleanPreferencesKey("ask_major_reroute")
        private val ALTERNATIVES_MODE        = stringPreferencesKey("alternatives_mode")
        private val ALTERNATIVE_COUNT        = intPreferencesKey("alternative_count")
        // Map appearance
        private val MAP_THEME                = stringPreferencesKey("map_theme")
        private val MAP_PERSPECTIVE          = stringPreferencesKey("map_perspective")
        private val HEADING_MODE             = stringPreferencesKey("heading_mode")
        private val SHOW_3D_BUILDINGS        = booleanPreferencesKey("show_3d_buildings")
        private val SHOW_TERRAIN             = booleanPreferencesKey("show_terrain")
        private val SHOW_HILL_SHADING        = booleanPreferencesKey("show_hill_shading")
        private val SHOW_TRAFFIC_LAYER       = booleanPreferencesKey("show_traffic_layer")
        private val SHOW_POI_LAYER           = booleanPreferencesKey("show_poi_layer")
        private val SHOW_GROUP_CARS_ON_MAP   = booleanPreferencesKey("show_group_cars_map")
        private val MAP_DETAIL_LEVEL         = stringPreferencesKey("map_detail_level")
        private val NAV_MAP_TILT             = booleanPreferencesKey("nav_map_tilt")
        private val KEEP_SCREEN_ON           = booleanPreferencesKey("keep_screen_on")
        // Auto-zoom
        private val AUTO_ZOOM                = booleanPreferencesKey("auto_zoom")
        private val AUTO_ZOOM_BY_SPEED       = booleanPreferencesKey("auto_zoom_speed")
        private val AUTO_ZOOM_INTERSECTIONS  = booleanPreferencesKey("auto_zoom_intersections")
        private val AUTO_ZOOM_ROUNDABOUTS    = booleanPreferencesKey("auto_zoom_roundabouts")
        private val AUTO_ZOOM_EXITS          = booleanPreferencesKey("auto_zoom_exits")
        private val AUTO_ZOOM_LANES          = booleanPreferencesKey("auto_zoom_lanes")
        // Safety
        private val SAFETY_ENABLED           = booleanPreferencesKey("safety_enabled")
        private val SHOW_SPEED_CAMERAS       = booleanPreferencesKey("show_speed_cameras")
        private val SHOW_RED_LIGHT_CAMERAS   = booleanPreferencesKey("show_red_light_cameras")
        private val SHOW_COMBINED_CAMERAS    = booleanPreferencesKey("show_combined_cameras")
        private val SHOW_AVERAGE_SPEED_ZONES = booleanPreferencesKey("show_avg_speed_zones")
        private val SHOW_MOBILE_ENFORCEMENT  = booleanPreferencesKey("show_mobile_enforcement")
        private val CAMERA_ALERT_DISTANCE    = stringPreferencesKey("camera_alert_distance")
        private val CAMERA_ALERT_STYLE       = stringPreferencesKey("camera_alert_style")
        private val SHOW_CAMERA_ON_MAP       = booleanPreferencesKey("show_camera_map")
        private val SHOW_CAMERA_DISTANCE     = booleanPreferencesKey("show_camera_distance")
        private val SHOW_CAMERA_SPEED_LIMIT  = booleanPreferencesKey("show_camera_speed_limit")
        private val SHOW_SCHOOL_ZONES        = booleanPreferencesKey("show_school_zones")
        private val SHOW_ROADWORKS_ALERTS    = booleanPreferencesKey("show_roadworks")
        private val SHOW_ACCIDENT_ALERTS     = booleanPreferencesKey("show_accidents")
        private val SHOW_WEATHER_WARNINGS    = booleanPreferencesKey("show_weather")
        private val SHOW_SHARP_CURVES        = booleanPreferencesKey("show_sharp_curves")
        // Speed
        private val SHOW_CURRENT_SPEED       = booleanPreferencesKey("show_current_speed")
        private val SHOW_SPEED_LIMIT         = booleanPreferencesKey("show_speed_limit")
        private val SPEED_WARNING_THRESHOLD  = stringPreferencesKey("speed_warning_threshold")
        private val SPEED_WARNING_VISUAL     = booleanPreferencesKey("speed_warning_visual")
        private val SPEED_WARNING_AUDIO      = booleanPreferencesKey("speed_warning_audio")
        // Road Ahead
        private val SHOW_ROAD_AHEAD          = booleanPreferencesKey("show_road_ahead")
        private val ROAD_AHEAD_MAX_ITEMS     = intPreferencesKey("road_ahead_max_items")
        private val ROAD_AHEAD_DISTANCE      = stringPreferencesKey("road_ahead_distance")
        private val ROAD_AHEAD_CAMERAS       = booleanPreferencesKey("road_ahead_cameras")
        private val ROAD_AHEAD_TRAFFIC       = booleanPreferencesKey("road_ahead_traffic")
        private val ROAD_AHEAD_ACCIDENTS     = booleanPreferencesKey("road_ahead_accidents")
        private val ROAD_AHEAD_ROADWORKS     = booleanPreferencesKey("road_ahead_roadworks")
        private val ROAD_AHEAD_WEATHER       = booleanPreferencesKey("road_ahead_weather")
        private val ROAD_AHEAD_FUEL          = booleanPreferencesKey("road_ahead_fuel")
        private val ROAD_AHEAD_REST_AREAS    = booleanPreferencesKey("road_ahead_rest")
        private val ROAD_AHEAD_BORDERS       = booleanPreferencesKey("road_ahead_borders")
        // Traffic
        private val TRAFFIC_ENABLED          = booleanPreferencesKey("traffic_enabled")
        private val TRAFFIC_AWARE_ETA        = booleanPreferencesKey("traffic_eta")
        private val TRAFFIC_AWARE_REROUTING  = booleanPreferencesKey("traffic_rerouting")
        private val TRAFFIC_ALERTS           = booleanPreferencesKey("traffic_alerts")
        // Traffic signals
        private val SIGNAL_INTELLIGENCE      = booleanPreferencesKey("signal_intelligence")
        private val SHOW_STATIC_LIGHTS       = booleanPreferencesKey("show_static_lights")
        private val SHOW_SIGNAL_TIMING       = booleanPreferencesKey("show_signal_timing")
        private val SHOW_GLOSA               = booleanPreferencesKey("show_glosa")
        private val SHOW_SIGNAL_DISTANCE     = booleanPreferencesKey("show_signal_distance")
        // Lane guidance
        private val SHOW_LANE_GUIDANCE       = booleanPreferencesKey("show_lane_guidance")
        private val LANE_EARLY_PREVIEW       = booleanPreferencesKey("lane_early_preview")
        private val SHOW_HIGHLIGHTED_LANES   = booleanPreferencesKey("show_highlighted_lanes")
        private val SHOW_LANE_ENDINGS        = booleanPreferencesKey("show_lane_endings")
        private val SHOW_LANE_ADDITIONS      = booleanPreferencesKey("show_lane_additions")
        private val SHOW_EXIT_ONLY_LANES     = booleanPreferencesKey("show_exit_only_lanes")
        private val SHOW_SIGNBOARDS          = booleanPreferencesKey("show_signboards")
        private val SHOW_EXIT_NUMBERS        = booleanPreferencesKey("show_exit_numbers")
        private val SHOW_ROAD_NUMBERS        = booleanPreferencesKey("show_road_numbers")
        private val SHOW_DESTINATION_NAMES   = booleanPreferencesKey("show_dest_names")
        // Smart Stops
        private val SMART_STOPS_ENABLED      = booleanPreferencesKey("smart_stops_enabled")
        private val SHOW_FUEL_BUTTON         = booleanPreferencesKey("show_fuel_button")
        private val SHOW_SHOP_BUTTON         = booleanPreferencesKey("show_shop_button")
        private val SHOW_TOILET_BUTTON       = booleanPreferencesKey("show_toilet_button")
        private val SHOW_COFFEE_BUTTON       = booleanPreferencesKey("show_coffee_button")
        private val SHOW_EV_BUTTON           = booleanPreferencesKey("show_ev_button")
        private val SMART_STOP_INSTANT       = booleanPreferencesKey("smart_stop_instant")
        private val SMART_STOP_ONLY_OPEN     = booleanPreferencesKey("smart_stop_only_open")
        private val SMART_STOP_MAX_DETOUR    = intPreferencesKey("smart_stop_max_detour")
        private val SMART_STOP_REQUIRE_PKG   = booleanPreferencesKey("smart_stop_require_parking")
        private val SMART_STOP_AVOID_UTURN   = booleanPreferencesKey("smart_stop_avoid_uturn")
        // Fuel
        private val FUEL_TYPE                = stringPreferencesKey("fuel_type")
        private val FUEL_PREFERENCE          = stringPreferencesKey("fuel_preference")
        private val FUEL_DETOUR_LIMIT        = stringPreferencesKey("fuel_detour_limit")
        // Supermarket
        private val SUPERMARKET_PREF         = stringPreferencesKey("supermarket_pref")
        // Border
        private val SHOW_BORDER_ALERTS       = booleanPreferencesKey("show_border_alerts")
        private val SMART_BORDER_MODE        = booleanPreferencesKey("smart_border_mode")
        private val BORDER_SPEED_LIMITS      = booleanPreferencesKey("border_speed_limits")
        private val BORDER_VIGNETTE          = booleanPreferencesKey("border_vignette")
        private val BORDER_TOLLS             = booleanPreferencesKey("border_tolls")
        private val BORDER_RULES             = booleanPreferencesKey("border_rules")
        // Road personality
        private val SHOW_ROAD_PERSONALITY    = booleanPreferencesKey("show_road_personality")
        // Voice
        private val VOICE_MODE               = stringPreferencesKey("voice_mode")
        private val VOICE_STREET_NAMES       = booleanPreferencesKey("voice_street_names")
        private val VOICE_ROAD_NUMBERS       = booleanPreferencesKey("voice_road_numbers")
        private val VOICE_TIMING             = stringPreferencesKey("voice_timing")
        // Group Drive
        private val GROUP_DRIVE_ENABLED      = booleanPreferencesKey("group_drive_enabled")
        private val SHOW_GROUP_PANEL         = booleanPreferencesKey("show_group_panel")
        private val SHARE_LOCATION_GROUP     = booleanPreferencesKey("share_location_group")
        private val SHARE_ETA_GROUP          = booleanPreferencesKey("share_eta_group")
        private val SHARE_SPEED_GROUP        = booleanPreferencesKey("share_speed_group")
        private val GROUP_GAP_THRESHOLD      = stringPreferencesKey("group_gap_threshold")
        private val GROUP_NOTIFY_BEHIND      = booleanPreferencesKey("group_notify_behind")
        private val GROUP_NOTIFY_STOPPED     = booleanPreferencesKey("group_notify_stopped")
        private val GROUP_AUTO_FUEL          = booleanPreferencesKey("group_auto_fuel")
        private val GROUP_AUTO_SHOP          = booleanPreferencesKey("group_auto_shop")
        // Privacy
        private val PRIVACY_PRESET           = stringPreferencesKey("privacy_preset")
        private val RECENT_SEARCH_HISTORY    = booleanPreferencesKey("recent_search_history")
        private val RECENT_DEST_HISTORY      = booleanPreferencesKey("recent_dest_history")
        private val TRIP_HISTORY             = booleanPreferencesKey("trip_history")
        private val TRIP_HISTORY_RETENTION   = stringPreferencesKey("trip_history_retention")
        private val ANALYTICS_ENABLED        = booleanPreferencesKey("analytics_enabled")
        private val CRASH_REPORTS_ENABLED    = booleanPreferencesKey("crash_reports_enabled")
        // Accessibility
        private val LARGE_BUTTONS            = booleanPreferencesKey("large_buttons")
        private val HIGH_CONTRAST            = booleanPreferencesKey("high_contrast")
        private val REDUCE_MOTION            = booleanPreferencesKey("reduce_motion")
        // Units
        private val DISTANCE_UNITS           = stringPreferencesKey("distance_units")
        // Saved places
        private val HOME_PLACE               = stringPreferencesKey("home_place")
        private val WORK_PLACE               = stringPreferencesKey("work_place")
    }

    private fun String.toSavedPlace(): SavedPlace? {
        val parts = split("|", limit = 3)
        if (parts.size < 3) return null
        return runCatching { SavedPlace(parts[0].toDouble(), parts[1].toDouble(), parts[2]) }.getOrNull()
    }

    private fun SavedPlace.encode() = "${lat}|${lng}|${label}"

    val settings: Flow<UserSettings> = dataStore.data.map { p ->
        UserSettings(
            vehicleType                 = enumOf<VehicleType>(p[VEHICLE_TYPE]) ?: VehicleType.ARROW,
            routeType                   = enumOf<RouteType>(p[ROUTE_TYPE]) ?: RouteType.FASTEST,
            avoidTolls                  = p[AVOID_TOLLS] ?: false,
            avoidHighways               = p[AVOID_HIGHWAYS] ?: false,
            avoidFerries                = p[AVOID_FERRIES] ?: false,
            avoidUnpavedRoads           = p[AVOID_UNPAVED] ?: false,
            avoidNarrowRoads            = p[AVOID_NARROW] ?: false,
            autoReroute                 = p[AUTO_REROUTE] ?: true,
            autoAcceptFasterRoute       = enumOf<FasterRouteThreshold>(p[AUTO_ACCEPT_FASTER]) ?: FasterRouteThreshold.SAVE_5_MIN,
            askBeforeMajorReroute       = p[ASK_BEFORE_MAJOR_REROUTE] ?: true,
            alternativesMode            = enumOf<AlternativesMode>(p[ALTERNATIVES_MODE]) ?: AlternativesMode.WHEN_MEANINGFUL,
            alternativeCount            = p[ALTERNATIVE_COUNT] ?: 2,
            mapTheme                    = enumOf<MapTheme>(p[MAP_THEME]) ?: MapTheme.AUTO,
            mapPerspective              = enumOf<MapPerspective>(p[MAP_PERSPECTIVE]) ?: MapPerspective.AUTO,
            headingMode                 = enumOf<HeadingMode>(p[HEADING_MODE]) ?: HeadingMode.HEADING_UP,
            show3dBuildings             = p[SHOW_3D_BUILDINGS] ?: true,
            showTerrain                 = p[SHOW_TERRAIN] ?: false,
            showHillShading             = p[SHOW_HILL_SHADING] ?: true,
            showTrafficLayer            = p[SHOW_TRAFFIC_LAYER] ?: true,
            showPoiLayer                = p[SHOW_POI_LAYER] ?: true,
            showGroupCarsOnMap          = p[SHOW_GROUP_CARS_ON_MAP] ?: true,
            mapDetailLevel              = enumOf<MapDetailLevel>(p[MAP_DETAIL_LEVEL]) ?: MapDetailLevel.BALANCED,
            navMapTilt                  = p[NAV_MAP_TILT] ?: true,
            keepScreenOn                = p[KEEP_SCREEN_ON] ?: true,
            autoZoom                    = p[AUTO_ZOOM] ?: true,
            autoZoomBySpeed             = p[AUTO_ZOOM_BY_SPEED] ?: true,
            autoZoomOnIntersections     = p[AUTO_ZOOM_INTERSECTIONS] ?: true,
            autoZoomOnRoundabouts       = p[AUTO_ZOOM_ROUNDABOUTS] ?: true,
            autoZoomOnMotorwayExits     = p[AUTO_ZOOM_EXITS] ?: true,
            autoZoomOnLaneGuidance      = p[AUTO_ZOOM_LANES] ?: true,
            safetyFeaturesEnabled       = p[SAFETY_ENABLED] ?: true,
            showSpeedCameras            = p[SHOW_SPEED_CAMERAS] ?: true,
            showRedLightCameras         = p[SHOW_RED_LIGHT_CAMERAS] ?: true,
            showCombinedCameras         = p[SHOW_COMBINED_CAMERAS] ?: true,
            showAverageSpeedZones       = p[SHOW_AVERAGE_SPEED_ZONES] ?: true,
            showMobileEnforcement       = p[SHOW_MOBILE_ENFORCEMENT] ?: true,
            cameraAlertDistance         = enumOf<CameraAlertDistance>(p[CAMERA_ALERT_DISTANCE]) ?: CameraAlertDistance.AUTO,
            cameraAlertStyle            = enumOf<CameraAlertStyle>(p[CAMERA_ALERT_STYLE]) ?: CameraAlertStyle.SOUND_AND_VISUAL,
            showCameraOnMap             = p[SHOW_CAMERA_ON_MAP] ?: true,
            showCameraDistance          = p[SHOW_CAMERA_DISTANCE] ?: true,
            showCameraSpeedLimit        = p[SHOW_CAMERA_SPEED_LIMIT] ?: true,
            showSchoolZones             = p[SHOW_SCHOOL_ZONES] ?: true,
            showRoadworksAlerts         = p[SHOW_ROADWORKS_ALERTS] ?: true,
            showAccidentAlerts          = p[SHOW_ACCIDENT_ALERTS] ?: true,
            showWeatherWarnings         = p[SHOW_WEATHER_WARNINGS] ?: true,
            showSharpCurveWarnings      = p[SHOW_SHARP_CURVES] ?: true,
            showCurrentSpeed            = p[SHOW_CURRENT_SPEED] ?: true,
            showSpeedLimit              = p[SHOW_SPEED_LIMIT] ?: true,
            speedWarningThreshold       = enumOf<SpeedWarningThreshold>(p[SPEED_WARNING_THRESHOLD]) ?: SpeedWarningThreshold.PLUS_5,
            speedWarningVisual          = p[SPEED_WARNING_VISUAL] ?: true,
            speedWarningAudio           = p[SPEED_WARNING_AUDIO] ?: true,
            showRoadAhead               = p[SHOW_ROAD_AHEAD] ?: true,
            roadAheadMaxItems           = p[ROAD_AHEAD_MAX_ITEMS] ?: 3,
            roadAheadDistance           = enumOf<RoadAheadDistance>(p[ROAD_AHEAD_DISTANCE]) ?: RoadAheadDistance.AUTO,
            roadAheadShowCameras        = p[ROAD_AHEAD_CAMERAS] ?: true,
            roadAheadShowTraffic        = p[ROAD_AHEAD_TRAFFIC] ?: true,
            roadAheadShowAccidents      = p[ROAD_AHEAD_ACCIDENTS] ?: true,
            roadAheadShowRoadworks      = p[ROAD_AHEAD_ROADWORKS] ?: true,
            roadAheadShowWeather        = p[ROAD_AHEAD_WEATHER] ?: true,
            roadAheadShowFuel           = p[ROAD_AHEAD_FUEL] ?: false,
            roadAheadShowRestAreas      = p[ROAD_AHEAD_REST_AREAS] ?: true,
            roadAheadShowBorders        = p[ROAD_AHEAD_BORDERS] ?: true,
            trafficFeaturesEnabled      = p[TRAFFIC_ENABLED] ?: true,
            trafficAwareEta             = p[TRAFFIC_AWARE_ETA] ?: true,
            trafficAwareRerouting       = p[TRAFFIC_AWARE_REROUTING] ?: true,
            trafficAlerts               = p[TRAFFIC_ALERTS] ?: true,
            trafficSignalIntelligence   = p[SIGNAL_INTELLIGENCE] ?: true,
            showStaticTrafficLights     = p[SHOW_STATIC_LIGHTS] ?: true,
            showSignalTiming            = p[SHOW_SIGNAL_TIMING] ?: false,
            showGlosa                   = p[SHOW_GLOSA] ?: false,
            showSignalDistance          = p[SHOW_SIGNAL_DISTANCE] ?: true,
            showLaneGuidance            = p[SHOW_LANE_GUIDANCE] ?: true,
            laneGuidanceEarlyPreview    = p[LANE_EARLY_PREVIEW] ?: true,
            showHighlightedRecommendedLanes = p[SHOW_HIGHLIGHTED_LANES] ?: true,
            showLaneEndings             = p[SHOW_LANE_ENDINGS] ?: true,
            showLaneAdditions           = p[SHOW_LANE_ADDITIONS] ?: true,
            showExitOnlyLanes           = p[SHOW_EXIT_ONLY_LANES] ?: true,
            showSignboards              = p[SHOW_SIGNBOARDS] ?: true,
            showExitNumbers             = p[SHOW_EXIT_NUMBERS] ?: true,
            showRoadNumbers             = p[SHOW_ROAD_NUMBERS] ?: true,
            showDestinationNames        = p[SHOW_DESTINATION_NAMES] ?: true,
            smartStopsEnabled           = p[SMART_STOPS_ENABLED] ?: true,
            showFuelButton              = p[SHOW_FUEL_BUTTON] ?: true,
            showShopButton              = p[SHOW_SHOP_BUTTON] ?: true,
            showToiletButton            = p[SHOW_TOILET_BUTTON] ?: false,
            showCoffeeButton            = p[SHOW_COFFEE_BUTTON] ?: false,
            showEvButton                = p[SHOW_EV_BUTTON] ?: false,
            smartStopInstantResult      = p[SMART_STOP_INSTANT] ?: true,
            smartStopOnlyOpen           = p[SMART_STOP_ONLY_OPEN] ?: true,
            smartStopMaxDetourMinutes   = p[SMART_STOP_MAX_DETOUR] ?: 10,
            smartStopRequireParking     = p[SMART_STOP_REQUIRE_PKG] ?: false,
            smartStopAvoidUTurn         = p[SMART_STOP_AVOID_UTURN] ?: true,
            fuelType                    = enumOf<FuelType>(p[FUEL_TYPE]) ?: FuelType.PETROL_E5,
            fuelPreference              = enumOf<FuelPreference>(p[FUEL_PREFERENCE]) ?: FuelPreference.BALANCED,
            fuelDetourLimit             = enumOf<FuelDetourLimit>(p[FUEL_DETOUR_LIMIT]) ?: FuelDetourLimit.MIN5,
            supermarketPreference       = enumOf<SupermarketPreference>(p[SUPERMARKET_PREF]) ?: SupermarketPreference.BEST_OVERALL,
            showBorderAlerts            = p[SHOW_BORDER_ALERTS] ?: true,
            smartBorderMode             = p[SMART_BORDER_MODE] ?: true,
            borderShowSpeedLimits       = p[BORDER_SPEED_LIMITS] ?: true,
            borderShowVignette          = p[BORDER_VIGNETTE] ?: true,
            borderShowTolls             = p[BORDER_TOLLS] ?: true,
            borderShowRules             = p[BORDER_RULES] ?: true,
            showRoadPersonality         = p[SHOW_ROAD_PERSONALITY] ?: true,
            voiceGuidanceMode           = enumOf<VoiceGuidanceMode>(p[VOICE_MODE]) ?: VoiceGuidanceMode.FULL,
            voiceIncludesStreetNames    = p[VOICE_STREET_NAMES] ?: true,
            voiceIncludesRoadNumbers    = p[VOICE_ROAD_NUMBERS] ?: true,
            voiceTiming                 = enumOf<VoiceTiming>(p[VOICE_TIMING]) ?: VoiceTiming.NORMAL,
            groupDriveEnabled           = p[GROUP_DRIVE_ENABLED] ?: true,
            showGroupPanel              = p[SHOW_GROUP_PANEL] ?: true,
            shareLocationWithGroup      = p[SHARE_LOCATION_GROUP] ?: true,
            shareEtaWithGroup           = p[SHARE_ETA_GROUP] ?: true,
            shareSpeedWithGroup         = p[SHARE_SPEED_GROUP] ?: false,
            groupGapThreshold           = enumOf<GroupGapThreshold>(p[GROUP_GAP_THRESHOLD]) ?: GroupGapThreshold.MIN2,
            groupNotifyVehicleBehind    = p[GROUP_NOTIFY_BEHIND] ?: true,
            groupNotifyVehicleStopped   = p[GROUP_NOTIFY_STOPPED] ?: true,
            groupAutoAcceptLeaderFuel   = p[GROUP_AUTO_FUEL] ?: false,
            groupAutoAcceptLeaderShop   = p[GROUP_AUTO_SHOP] ?: false,
            privacyPreset               = enumOf<PrivacyPreset>(p[PRIVACY_PRESET]) ?: PrivacyPreset.BALANCED,
            recentSearchHistoryEnabled  = p[RECENT_SEARCH_HISTORY] ?: true,
            recentDestinationHistoryEnabled = p[RECENT_DEST_HISTORY] ?: true,
            tripHistoryEnabled          = p[TRIP_HISTORY] ?: true,
            tripHistoryRetention        = enumOf<TripHistoryRetention>(p[TRIP_HISTORY_RETENTION]) ?: TripHistoryRetention.DAYS30,
            analyticsEnabled            = p[ANALYTICS_ENABLED] ?: false,
            crashReportsEnabled         = p[CRASH_REPORTS_ENABLED] ?: true,
            largeButtonsMode            = p[LARGE_BUTTONS] ?: false,
            highContrastMode            = p[HIGH_CONTRAST] ?: false,
            reduceMotion                = p[REDUCE_MOTION] ?: false,
            units                       = enumOf<DistanceUnits>(p[DISTANCE_UNITS]) ?: DistanceUnits.METRIC,
            homePlace                   = p[HOME_PLACE]?.toSavedPlace(),
            workPlace                   = p[WORK_PLACE]?.toSavedPlace(),
        )
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    suspend fun setVehicleType(v: VehicleType)               { dataStore.edit { it[VEHICLE_TYPE] = v.name } }
    suspend fun setRouteType(v: RouteType)                   { dataStore.edit { it[ROUTE_TYPE] = v.name } }
    suspend fun setAvoidTolls(v: Boolean)                    { dataStore.edit { it[AVOID_TOLLS] = v } }
    suspend fun setAvoidHighways(v: Boolean)                 { dataStore.edit { it[AVOID_HIGHWAYS] = v } }
    suspend fun setAvoidFerries(v: Boolean)                  { dataStore.edit { it[AVOID_FERRIES] = v } }
    suspend fun setAvoidUnpavedRoads(v: Boolean)             { dataStore.edit { it[AVOID_UNPAVED] = v } }
    suspend fun setAvoidNarrowRoads(v: Boolean)              { dataStore.edit { it[AVOID_NARROW] = v } }
    suspend fun setAutoReroute(v: Boolean)                   { dataStore.edit { it[AUTO_REROUTE] = v } }
    suspend fun setAutoAcceptFasterRoute(v: FasterRouteThreshold) { dataStore.edit { it[AUTO_ACCEPT_FASTER] = v.name } }
    suspend fun setAskBeforeMajorReroute(v: Boolean)         { dataStore.edit { it[ASK_BEFORE_MAJOR_REROUTE] = v } }
    suspend fun setAlternativesMode(v: AlternativesMode)     { dataStore.edit { it[ALTERNATIVES_MODE] = v.name } }
    suspend fun setAlternativeCount(v: Int)                  { dataStore.edit { it[ALTERNATIVE_COUNT] = v } }
    suspend fun setMapTheme(v: MapTheme)                     { dataStore.edit { it[MAP_THEME] = v.name } }
    suspend fun setMapPerspective(v: MapPerspective)         { dataStore.edit { it[MAP_PERSPECTIVE] = v.name } }
    suspend fun setHeadingMode(v: HeadingMode)               { dataStore.edit { it[HEADING_MODE] = v.name } }
    suspend fun setShow3dBuildings(v: Boolean)               { dataStore.edit { it[SHOW_3D_BUILDINGS] = v } }
    suspend fun setShowTerrain(v: Boolean)                   { dataStore.edit { it[SHOW_TERRAIN] = v } }
    suspend fun setShowHillShading(v: Boolean)               { dataStore.edit { it[SHOW_HILL_SHADING] = v } }
    suspend fun setShowTrafficLayer(v: Boolean)              { dataStore.edit { it[SHOW_TRAFFIC_LAYER] = v } }
    suspend fun setShowPoiLayer(v: Boolean)                  { dataStore.edit { it[SHOW_POI_LAYER] = v } }
    suspend fun setShowGroupCarsOnMap(v: Boolean)            { dataStore.edit { it[SHOW_GROUP_CARS_ON_MAP] = v } }
    suspend fun setMapDetailLevel(v: MapDetailLevel)         { dataStore.edit { it[MAP_DETAIL_LEVEL] = v.name } }
    suspend fun setNavMapTilt(v: Boolean)                    { dataStore.edit { it[NAV_MAP_TILT] = v } }
    suspend fun setKeepScreenOn(v: Boolean)                  { dataStore.edit { it[KEEP_SCREEN_ON] = v } }
    suspend fun setAutoZoom(v: Boolean)                      { dataStore.edit { it[AUTO_ZOOM] = v } }
    suspend fun setAutoZoomBySpeed(v: Boolean)               { dataStore.edit { it[AUTO_ZOOM_BY_SPEED] = v } }
    suspend fun setAutoZoomOnIntersections(v: Boolean)       { dataStore.edit { it[AUTO_ZOOM_INTERSECTIONS] = v } }
    suspend fun setAutoZoomOnRoundabouts(v: Boolean)         { dataStore.edit { it[AUTO_ZOOM_ROUNDABOUTS] = v } }
    suspend fun setAutoZoomOnMotorwayExits(v: Boolean)       { dataStore.edit { it[AUTO_ZOOM_EXITS] = v } }
    suspend fun setAutoZoomOnLaneGuidance(v: Boolean)        { dataStore.edit { it[AUTO_ZOOM_LANES] = v } }
    suspend fun setSafetyFeaturesEnabled(v: Boolean)         { dataStore.edit { it[SAFETY_ENABLED] = v } }
    suspend fun setShowSpeedCameras(v: Boolean)              { dataStore.edit { it[SHOW_SPEED_CAMERAS] = v } }
    suspend fun setShowRedLightCameras(v: Boolean)           { dataStore.edit { it[SHOW_RED_LIGHT_CAMERAS] = v } }
    suspend fun setShowCombinedCameras(v: Boolean)           { dataStore.edit { it[SHOW_COMBINED_CAMERAS] = v } }
    suspend fun setShowAverageSpeedZones(v: Boolean)         { dataStore.edit { it[SHOW_AVERAGE_SPEED_ZONES] = v } }
    suspend fun setShowMobileEnforcement(v: Boolean)         { dataStore.edit { it[SHOW_MOBILE_ENFORCEMENT] = v } }
    suspend fun setCameraAlertDistance(v: CameraAlertDistance) { dataStore.edit { it[CAMERA_ALERT_DISTANCE] = v.name } }
    suspend fun setCameraAlertStyle(v: CameraAlertStyle)     { dataStore.edit { it[CAMERA_ALERT_STYLE] = v.name } }
    suspend fun setShowCameraOnMap(v: Boolean)               { dataStore.edit { it[SHOW_CAMERA_ON_MAP] = v } }
    suspend fun setShowCameraDistance(v: Boolean)            { dataStore.edit { it[SHOW_CAMERA_DISTANCE] = v } }
    suspend fun setShowCameraSpeedLimit(v: Boolean)          { dataStore.edit { it[SHOW_CAMERA_SPEED_LIMIT] = v } }
    suspend fun setShowSchoolZones(v: Boolean)               { dataStore.edit { it[SHOW_SCHOOL_ZONES] = v } }
    suspend fun setShowRoadworksAlerts(v: Boolean)           { dataStore.edit { it[SHOW_ROADWORKS_ALERTS] = v } }
    suspend fun setShowAccidentAlerts(v: Boolean)            { dataStore.edit { it[SHOW_ACCIDENT_ALERTS] = v } }
    suspend fun setShowWeatherWarnings(v: Boolean)           { dataStore.edit { it[SHOW_WEATHER_WARNINGS] = v } }
    suspend fun setShowSharpCurveWarnings(v: Boolean)        { dataStore.edit { it[SHOW_SHARP_CURVES] = v } }
    suspend fun setShowCurrentSpeed(v: Boolean)              { dataStore.edit { it[SHOW_CURRENT_SPEED] = v } }
    suspend fun setShowSpeedLimit(v: Boolean)                { dataStore.edit { it[SHOW_SPEED_LIMIT] = v } }
    suspend fun setSpeedWarningThreshold(v: SpeedWarningThreshold) { dataStore.edit { it[SPEED_WARNING_THRESHOLD] = v.name } }
    suspend fun setSpeedWarningVisual(v: Boolean)            { dataStore.edit { it[SPEED_WARNING_VISUAL] = v } }
    suspend fun setSpeedWarningAudio(v: Boolean)             { dataStore.edit { it[SPEED_WARNING_AUDIO] = v } }
    suspend fun setShowRoadAhead(v: Boolean)                 { dataStore.edit { it[SHOW_ROAD_AHEAD] = v } }
    suspend fun setRoadAheadMaxItems(v: Int)                 { dataStore.edit { it[ROAD_AHEAD_MAX_ITEMS] = v } }
    suspend fun setRoadAheadDistance(v: RoadAheadDistance)   { dataStore.edit { it[ROAD_AHEAD_DISTANCE] = v.name } }
    suspend fun setRoadAheadShowCameras(v: Boolean)          { dataStore.edit { it[ROAD_AHEAD_CAMERAS] = v } }
    suspend fun setRoadAheadShowTraffic(v: Boolean)          { dataStore.edit { it[ROAD_AHEAD_TRAFFIC] = v } }
    suspend fun setRoadAheadShowAccidents(v: Boolean)        { dataStore.edit { it[ROAD_AHEAD_ACCIDENTS] = v } }
    suspend fun setRoadAheadShowRoadworks(v: Boolean)        { dataStore.edit { it[ROAD_AHEAD_ROADWORKS] = v } }
    suspend fun setRoadAheadShowWeather(v: Boolean)          { dataStore.edit { it[ROAD_AHEAD_WEATHER] = v } }
    suspend fun setRoadAheadShowFuel(v: Boolean)             { dataStore.edit { it[ROAD_AHEAD_FUEL] = v } }
    suspend fun setRoadAheadShowRestAreas(v: Boolean)        { dataStore.edit { it[ROAD_AHEAD_REST_AREAS] = v } }
    suspend fun setRoadAheadShowBorders(v: Boolean)          { dataStore.edit { it[ROAD_AHEAD_BORDERS] = v } }
    suspend fun setTrafficFeaturesEnabled(v: Boolean)        { dataStore.edit { it[TRAFFIC_ENABLED] = v } }
    suspend fun setTrafficAwareEta(v: Boolean)               { dataStore.edit { it[TRAFFIC_AWARE_ETA] = v } }
    suspend fun setTrafficAwareRerouting(v: Boolean)         { dataStore.edit { it[TRAFFIC_AWARE_REROUTING] = v } }
    suspend fun setTrafficAlerts(v: Boolean)                 { dataStore.edit { it[TRAFFIC_ALERTS] = v } }
    suspend fun setTrafficSignalIntelligence(v: Boolean)     { dataStore.edit { it[SIGNAL_INTELLIGENCE] = v } }
    suspend fun setShowStaticTrafficLights(v: Boolean)       { dataStore.edit { it[SHOW_STATIC_LIGHTS] = v } }
    suspend fun setShowSignalTiming(v: Boolean)              { dataStore.edit { it[SHOW_SIGNAL_TIMING] = v } }
    suspend fun setShowGlosa(v: Boolean)                     { dataStore.edit { it[SHOW_GLOSA] = v } }
    suspend fun setShowSignalDistance(v: Boolean)            { dataStore.edit { it[SHOW_SIGNAL_DISTANCE] = v } }
    suspend fun setShowLaneGuidance(v: Boolean)              { dataStore.edit { it[SHOW_LANE_GUIDANCE] = v } }
    suspend fun setLaneGuidanceEarlyPreview(v: Boolean)      { dataStore.edit { it[LANE_EARLY_PREVIEW] = v } }
    suspend fun setShowHighlightedRecommendedLanes(v: Boolean) { dataStore.edit { it[SHOW_HIGHLIGHTED_LANES] = v } }
    suspend fun setShowLaneEndings(v: Boolean)               { dataStore.edit { it[SHOW_LANE_ENDINGS] = v } }
    suspend fun setShowLaneAdditions(v: Boolean)             { dataStore.edit { it[SHOW_LANE_ADDITIONS] = v } }
    suspend fun setShowExitOnlyLanes(v: Boolean)             { dataStore.edit { it[SHOW_EXIT_ONLY_LANES] = v } }
    suspend fun setShowSignboards(v: Boolean)                { dataStore.edit { it[SHOW_SIGNBOARDS] = v } }
    suspend fun setShowExitNumbers(v: Boolean)               { dataStore.edit { it[SHOW_EXIT_NUMBERS] = v } }
    suspend fun setShowRoadNumbers(v: Boolean)               { dataStore.edit { it[SHOW_ROAD_NUMBERS] = v } }
    suspend fun setShowDestinationNames(v: Boolean)          { dataStore.edit { it[SHOW_DESTINATION_NAMES] = v } }
    suspend fun setSmartStopsEnabled(v: Boolean)             { dataStore.edit { it[SMART_STOPS_ENABLED] = v } }
    suspend fun setShowFuelButton(v: Boolean)                { dataStore.edit { it[SHOW_FUEL_BUTTON] = v } }
    suspend fun setShowShopButton(v: Boolean)                { dataStore.edit { it[SHOW_SHOP_BUTTON] = v } }
    suspend fun setShowToiletButton(v: Boolean)              { dataStore.edit { it[SHOW_TOILET_BUTTON] = v } }
    suspend fun setShowCoffeeButton(v: Boolean)              { dataStore.edit { it[SHOW_COFFEE_BUTTON] = v } }
    suspend fun setShowEvButton(v: Boolean)                  { dataStore.edit { it[SHOW_EV_BUTTON] = v } }
    suspend fun setSmartStopInstantResult(v: Boolean)        { dataStore.edit { it[SMART_STOP_INSTANT] = v } }
    suspend fun setSmartStopOnlyOpen(v: Boolean)             { dataStore.edit { it[SMART_STOP_ONLY_OPEN] = v } }
    suspend fun setSmartStopMaxDetourMinutes(v: Int)         { dataStore.edit { it[SMART_STOP_MAX_DETOUR] = v } }
    suspend fun setSmartStopRequireParking(v: Boolean)       { dataStore.edit { it[SMART_STOP_REQUIRE_PKG] = v } }
    suspend fun setSmartStopAvoidUTurn(v: Boolean)           { dataStore.edit { it[SMART_STOP_AVOID_UTURN] = v } }
    suspend fun setFuelType(v: FuelType)                     { dataStore.edit { it[FUEL_TYPE] = v.name } }
    suspend fun setFuelPreference(v: FuelPreference)         { dataStore.edit { it[FUEL_PREFERENCE] = v.name } }
    suspend fun setFuelDetourLimit(v: FuelDetourLimit)       { dataStore.edit { it[FUEL_DETOUR_LIMIT] = v.name } }
    suspend fun setSupermarketPreference(v: SupermarketPreference) { dataStore.edit { it[SUPERMARKET_PREF] = v.name } }
    suspend fun setShowBorderAlerts(v: Boolean)              { dataStore.edit { it[SHOW_BORDER_ALERTS] = v } }
    suspend fun setSmartBorderMode(v: Boolean)               { dataStore.edit { it[SMART_BORDER_MODE] = v } }
    suspend fun setBorderShowSpeedLimits(v: Boolean)         { dataStore.edit { it[BORDER_SPEED_LIMITS] = v } }
    suspend fun setBorderShowVignette(v: Boolean)            { dataStore.edit { it[BORDER_VIGNETTE] = v } }
    suspend fun setBorderShowTolls(v: Boolean)               { dataStore.edit { it[BORDER_TOLLS] = v } }
    suspend fun setBorderShowRules(v: Boolean)               { dataStore.edit { it[BORDER_RULES] = v } }
    suspend fun setShowRoadPersonality(v: Boolean)           { dataStore.edit { it[SHOW_ROAD_PERSONALITY] = v } }
    suspend fun setVoiceGuidanceMode(v: VoiceGuidanceMode)   { dataStore.edit { it[VOICE_MODE] = v.name } }
    suspend fun setVoiceIncludesStreetNames(v: Boolean)      { dataStore.edit { it[VOICE_STREET_NAMES] = v } }
    suspend fun setVoiceIncludesRoadNumbers(v: Boolean)      { dataStore.edit { it[VOICE_ROAD_NUMBERS] = v } }
    suspend fun setVoiceTiming(v: VoiceTiming)               { dataStore.edit { it[VOICE_TIMING] = v.name } }
    suspend fun setGroupDriveEnabled(v: Boolean)             { dataStore.edit { it[GROUP_DRIVE_ENABLED] = v } }
    suspend fun setShowGroupPanel(v: Boolean)                { dataStore.edit { it[SHOW_GROUP_PANEL] = v } }
    suspend fun setShareLocationWithGroup(v: Boolean)        { dataStore.edit { it[SHARE_LOCATION_GROUP] = v } }
    suspend fun setShareEtaWithGroup(v: Boolean)             { dataStore.edit { it[SHARE_ETA_GROUP] = v } }
    suspend fun setShareSpeedWithGroup(v: Boolean)           { dataStore.edit { it[SHARE_SPEED_GROUP] = v } }
    suspend fun setGroupGapThreshold(v: GroupGapThreshold)   { dataStore.edit { it[GROUP_GAP_THRESHOLD] = v.name } }
    suspend fun setGroupNotifyVehicleBehind(v: Boolean)      { dataStore.edit { it[GROUP_NOTIFY_BEHIND] = v } }
    suspend fun setGroupNotifyVehicleStopped(v: Boolean)     { dataStore.edit { it[GROUP_NOTIFY_STOPPED] = v } }
    suspend fun setGroupAutoAcceptLeaderFuel(v: Boolean)     { dataStore.edit { it[GROUP_AUTO_FUEL] = v } }
    suspend fun setGroupAutoAcceptLeaderShop(v: Boolean)     { dataStore.edit { it[GROUP_AUTO_SHOP] = v } }
    suspend fun setPrivacyPreset(v: PrivacyPreset)           { dataStore.edit { it[PRIVACY_PRESET] = v.name } }
    suspend fun setRecentSearchHistoryEnabled(v: Boolean)    { dataStore.edit { it[RECENT_SEARCH_HISTORY] = v } }
    suspend fun setRecentDestinationHistoryEnabled(v: Boolean) { dataStore.edit { it[RECENT_DEST_HISTORY] = v } }
    suspend fun setTripHistoryEnabled(v: Boolean)            { dataStore.edit { it[TRIP_HISTORY] = v } }
    suspend fun setTripHistoryRetention(v: TripHistoryRetention) { dataStore.edit { it[TRIP_HISTORY_RETENTION] = v.name } }
    suspend fun setAnalyticsEnabled(v: Boolean)              { dataStore.edit { it[ANALYTICS_ENABLED] = v } }
    suspend fun setCrashReportsEnabled(v: Boolean)           { dataStore.edit { it[CRASH_REPORTS_ENABLED] = v } }
    suspend fun setLargeButtonsMode(v: Boolean)              { dataStore.edit { it[LARGE_BUTTONS] = v } }
    suspend fun setHighContrastMode(v: Boolean)              { dataStore.edit { it[HIGH_CONTRAST] = v } }
    suspend fun setReduceMotion(v: Boolean)                  { dataStore.edit { it[REDUCE_MOTION] = v } }
    suspend fun setUnits(v: DistanceUnits)                   { dataStore.edit { it[DISTANCE_UNITS] = v.name } }

    suspend fun setHomePlace(v: SavedPlace?)  {
        dataStore.edit { if (v != null) it[HOME_PLACE] = v.encode() else it.remove(HOME_PLACE) }
    }
    suspend fun setWorkPlace(v: SavedPlace?)  {
        dataStore.edit { if (v != null) it[WORK_PLACE] = v.encode() else it.remove(WORK_PLACE) }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
