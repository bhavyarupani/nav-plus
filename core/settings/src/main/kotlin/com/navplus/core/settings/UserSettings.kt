package com.navplus.core.settings

// ── Enums ────────────────────────────────────────────────────────────────────

enum class DistanceUnits { METRIC, IMPERIAL }

enum class RouteType(val label: String) {
    FASTEST("Fastest"),
    SCENIC("Scenic"),
    AVOID_HIGHWAYS("Avoid motorways"),
    PREFER_MAJOR("Prefer major roads"),
}

enum class FasterRouteThreshold(val label: String) {
    NEVER("Never"),
    SAVE_5_MIN("Save > 5 min"),
    SAVE_10_MIN("Save > 10 min"),
    ALWAYS("Always"),
}

enum class AlternativesMode(val label: String) {
    ALWAYS("Always show"),
    WHEN_MEANINGFUL("Only when meaningful"),
    NEVER("Never"),
}

enum class MapTheme(val label: String) {
    AUTO("Auto"),
    DAY("Day"),
    NIGHT("Night"),
}

enum class MapPerspective(val label: String) {
    AUTO("Auto"),
    TWO_D("2D"),
    THREE_D("3D"),
}

enum class HeadingMode(val label: String) {
    HEADING_UP("Heading up"),
    NORTH_UP("North up"),
    AUTO("Auto"),
}

enum class MapDetailLevel(val label: String) {
    MINIMAL("Minimal"),
    BALANCED("Balanced"),
    DETAILED("Detailed"),
    CUSTOM("Custom"),
}

enum class CameraAlertDistance(val label: String) {
    AUTO("Automatic"),
    M500("500 m"),
    KM1("1 km"),
    KM2("2 km"),
}

enum class CameraAlertStyle(val label: String) {
    VISUAL_ONLY("Visual only"),
    SOUND_AND_VISUAL("Sound + visual"),
    VOICE_AND_VISUAL("Voice + visual"),
    ALL("Sound + voice + visual"),
}

enum class SpeedWarningThreshold(val label: String) {
    EXACT("Exact limit"),
    PLUS_3("+3 km/h"),
    PLUS_5("+5 km/h"),
    PLUS_10("+10 km/h"),
}

enum class RoadAheadDistance(val label: String) {
    AUTO("Auto"),
    KM5("5 km"),
    KM10("10 km"),
    KM25("25 km"),
    KM50("50 km"),
    KM100("100 km"),
}

enum class FuelType(val label: String) {
    PETROL_E5("Petrol E5"),
    E10("E10"),
    DIESEL("Diesel"),
    LPG("LPG"),
    OTHER("Other"),
}

enum class FuelPreference(val label: String) {
    BALANCED("Balanced"),
    MIN_DETOUR("Minimum detour"),
    CHEAPEST("Cheapest"),
    PREFER_MOTORWAY("Prefer motorway stations"),
    AVOID_MOTORWAY("Avoid motorway prices"),
}

enum class FuelDetourLimit(val label: String) {
    AUTO("Automatic"),
    MIN2("2 min"),
    MIN5("5 min"),
    MIN10("10 min"),
    MIN15("15 min"),
}

enum class SupermarketPreference(val label: String) {
    BEST_OVERALL("Best overall"),
    MIN_DETOUR("Minimum detour"),
    LARGEST("Largest store"),
    PREFERRED_BRAND("Preferred brand"),
    OPEN_LONGEST("Open longest"),
}

enum class VoiceGuidanceMode(val label: String) {
    FULL("Full guidance"),
    IMPORTANT_ONLY("Important only"),
    ALERTS_ONLY("Alerts only"),
    MUTED("Muted"),
}

enum class VoiceTiming(val label: String) {
    EARLY("Early"),
    NORMAL("Normal"),
    LATE("Late"),
}

enum class GroupGapThreshold(val label: String) {
    AUTO("Automatic"),
    MIN1("1 min"),
    MIN2("2 min"),
    MIN5("5 min"),
    MIN10("10 min"),
}

enum class TripHistoryRetention(val label: String) {
    NEVER("Never"),
    DAYS7("7 days"),
    DAYS30("30 days"),
    DAYS90("90 days"),
    FOREVER("Forever"),
}

enum class PrivacyPreset(val label: String) {
    BALANCED("Balanced"),
    PRIVATE("Private"),
    CUSTOM("Custom"),
}

// ── Saved places ─────────────────────────────────────────────────────────────

data class SavedPlace(val lat: Double, val lng: Double, val label: String)

// ── Data class ────────────────────────────────────────────────────────────────

data class UserSettings(

    // ── Vehicle ──────────────────────────────────────────────────────────────
    val vehicleType: VehicleType = VehicleType.ARROW,

    // ── Route ────────────────────────────────────────────────────────────────
    val routeType: RouteType = RouteType.FASTEST,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpavedRoads: Boolean = false,
    val avoidNarrowRoads: Boolean = false,
    val autoReroute: Boolean = true,
    val autoAcceptFasterRoute: FasterRouteThreshold = FasterRouteThreshold.SAVE_5_MIN,
    val askBeforeMajorReroute: Boolean = true,
    val alternativesMode: AlternativesMode = AlternativesMode.WHEN_MEANINGFUL,
    val alternativeCount: Int = 2,

    // ── Map appearance ────────────────────────────────────────────────────────
    val mapTheme: MapTheme = MapTheme.AUTO,
    val mapPerspective: MapPerspective = MapPerspective.AUTO,
    val headingMode: HeadingMode = HeadingMode.HEADING_UP,
    val show3dBuildings: Boolean = true,
    val showTerrain: Boolean = false,
    val showHillShading: Boolean = true,
    val showTrafficLayer: Boolean = true,
    val showPoiLayer: Boolean = true,
    val showGroupCarsOnMap: Boolean = true,
    val mapDetailLevel: MapDetailLevel = MapDetailLevel.BALANCED,
    val navMapTilt: Boolean = true,
    val keepScreenOn: Boolean = true,

    // ── Auto-zoom ─────────────────────────────────────────────────────────────
    val autoZoom: Boolean = true,
    val autoZoomBySpeed: Boolean = true,
    val autoZoomOnIntersections: Boolean = true,
    val autoZoomOnRoundabouts: Boolean = true,
    val autoZoomOnMotorwayExits: Boolean = true,
    val autoZoomOnLaneGuidance: Boolean = true,

    // ── Safety & cameras ──────────────────────────────────────────────────────
    val safetyFeaturesEnabled: Boolean = true,
    val showSpeedCameras: Boolean = true,
    val showRedLightCameras: Boolean = true,
    val showCombinedCameras: Boolean = true,
    val showAverageSpeedZones: Boolean = true,
    val showMobileEnforcement: Boolean = true,
    val cameraAlertDistance: CameraAlertDistance = CameraAlertDistance.AUTO,
    val cameraAlertStyle: CameraAlertStyle = CameraAlertStyle.SOUND_AND_VISUAL,
    val showCameraOnMap: Boolean = true,
    val showCameraDistance: Boolean = true,
    val showCameraSpeedLimit: Boolean = true,
    val showSchoolZones: Boolean = true,
    val showRoadworksAlerts: Boolean = true,
    val showAccidentAlerts: Boolean = true,
    val showWeatherWarnings: Boolean = true,
    val showSharpCurveWarnings: Boolean = true,

    // ── Speed display ─────────────────────────────────────────────────────────
    val showCurrentSpeed: Boolean = true,
    val showSpeedLimit: Boolean = true,
    val speedWarningThreshold: SpeedWarningThreshold = SpeedWarningThreshold.PLUS_5,
    val speedWarningVisual: Boolean = true,
    val speedWarningAudio: Boolean = true,

    // ── Road Ahead ────────────────────────────────────────────────────────────
    val showRoadAhead: Boolean = true,
    val roadAheadMaxItems: Int = 3,
    val roadAheadDistance: RoadAheadDistance = RoadAheadDistance.AUTO,
    val roadAheadShowCameras: Boolean = true,
    val roadAheadShowTraffic: Boolean = true,
    val roadAheadShowAccidents: Boolean = true,
    val roadAheadShowRoadworks: Boolean = true,
    val roadAheadShowWeather: Boolean = true,
    val roadAheadShowFuel: Boolean = false,
    val roadAheadShowRestAreas: Boolean = true,
    val roadAheadShowBorders: Boolean = true,

    // ── Traffic ───────────────────────────────────────────────────────────────
    val trafficFeaturesEnabled: Boolean = true,
    val trafficAwareEta: Boolean = true,
    val trafficAwareRerouting: Boolean = true,
    val trafficAlerts: Boolean = true,

    // ── Traffic signals ───────────────────────────────────────────────────────
    val trafficSignalIntelligence: Boolean = true,
    val showStaticTrafficLights: Boolean = true,
    val showSignalTiming: Boolean = false,
    val showGlosa: Boolean = false,
    val showSignalDistance: Boolean = true,

    // ── Lane guidance & signs ─────────────────────────────────────────────────
    val showLaneGuidance: Boolean = true,
    val laneGuidanceEarlyPreview: Boolean = true,
    val showHighlightedRecommendedLanes: Boolean = true,
    val showLaneEndings: Boolean = true,
    val showLaneAdditions: Boolean = true,
    val showExitOnlyLanes: Boolean = true,
    val showSignboards: Boolean = true,
    val showExitNumbers: Boolean = true,
    val showRoadNumbers: Boolean = true,
    val showDestinationNames: Boolean = true,

    // ── Smart Stops ───────────────────────────────────────────────────────────
    val smartStopsEnabled: Boolean = true,
    val showFuelButton: Boolean = true,
    val showShopButton: Boolean = true,
    val showToiletButton: Boolean = false,
    val showCoffeeButton: Boolean = false,
    val showEvButton: Boolean = false,
    val smartStopInstantResult: Boolean = true,
    val smartStopOnlyOpen: Boolean = true,
    val smartStopMaxDetourMinutes: Int = 10,
    val smartStopRequireParking: Boolean = false,
    val smartStopAvoidUTurn: Boolean = true,

    // ── Fuel ─────────────────────────────────────────────────────────────────
    val fuelType: FuelType = FuelType.PETROL_E5,
    val fuelPreference: FuelPreference = FuelPreference.BALANCED,
    val fuelDetourLimit: FuelDetourLimit = FuelDetourLimit.MIN5,

    // ── Supermarket ───────────────────────────────────────────────────────────
    val supermarketPreference: SupermarketPreference = SupermarketPreference.BEST_OVERALL,

    // ── Border ────────────────────────────────────────────────────────────────
    val showBorderAlerts: Boolean = true,
    val smartBorderMode: Boolean = true,
    val borderShowSpeedLimits: Boolean = true,
    val borderShowVignette: Boolean = true,
    val borderShowTolls: Boolean = true,
    val borderShowRules: Boolean = true,

    // ── Road personality ──────────────────────────────────────────────────────
    val showRoadPersonality: Boolean = true,

    // ── Real-world feel ───────────────────────────────────────────────────────
    val realWorldFeelEnabled: Boolean = true,
    val showVisibleAircraft: Boolean = true,
    val showAirportApproach: Boolean = true,
    val showRailCrossingIntelligence: Boolean = true,
    val showSkyAndLightReality: Boolean = true,
    val showSunGlareWarning: Boolean = true,
    val showRoadsideLandmarks: Boolean = true,
    val showWaterFerryBridgeMoments: Boolean = true,
    val showWildlifeRiskAtmosphere: Boolean = true,
    val showEventCrowdPulse: Boolean = true,
    val showRoadFeelMode: Boolean = true,

    // ── Voice & alerts ────────────────────────────────────────────────────────
    val voiceGuidanceMode: VoiceGuidanceMode = VoiceGuidanceMode.FULL,
    val voiceIncludesStreetNames: Boolean = true,
    val voiceIncludesRoadNumbers: Boolean = true,
    val voiceTiming: VoiceTiming = VoiceTiming.NORMAL,

    // ── Group Drive ───────────────────────────────────────────────────────────
    val groupDriveEnabled: Boolean = true,
    val showGroupPanel: Boolean = true,
    val shareLocationWithGroup: Boolean = true,
    val shareEtaWithGroup: Boolean = true,
    val shareSpeedWithGroup: Boolean = false,
    val groupGapThreshold: GroupGapThreshold = GroupGapThreshold.MIN2,
    val groupNotifyVehicleBehind: Boolean = true,
    val groupNotifyVehicleStopped: Boolean = true,
    val groupAutoAcceptLeaderFuel: Boolean = false,
    val groupAutoAcceptLeaderShop: Boolean = false,

    // ── Privacy ───────────────────────────────────────────────────────────────
    val privacyPreset: PrivacyPreset = PrivacyPreset.BALANCED,
    val recentSearchHistoryEnabled: Boolean = true,
    val recentDestinationHistoryEnabled: Boolean = true,
    val tripHistoryEnabled: Boolean = true,
    val tripHistoryRetention: TripHistoryRetention = TripHistoryRetention.DAYS30,
    val analyticsEnabled: Boolean = false,
    val crashReportsEnabled: Boolean = true,

    // ── Accessibility ─────────────────────────────────────────────────────────
    val largeButtonsMode: Boolean = false,
    val highContrastMode: Boolean = false,
    val reduceMotion: Boolean = false,

    // ── Units ─────────────────────────────────────────────────────────────────
    val units: DistanceUnits = DistanceUnits.METRIC,

    // ── Saved places ──────────────────────────────────────────────────────────
    val homePlace: SavedPlace? = null,
    val workPlace: SavedPlace? = null,
)
