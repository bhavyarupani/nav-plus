package com.navplus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.settings.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // Vehicle
    fun setVehicleType(v: VehicleType)               = launch { repo.setVehicleType(v) }

    // Route
    fun setRouteType(v: RouteType)                   = launch { repo.setRouteType(v) }
    fun setAvoidTolls(v: Boolean)                    = launch { repo.setAvoidTolls(v) }
    fun setAvoidHighways(v: Boolean)                 = launch { repo.setAvoidHighways(v) }
    fun setAvoidFerries(v: Boolean)                  = launch { repo.setAvoidFerries(v) }
    fun setAvoidUnpavedRoads(v: Boolean)             = launch { repo.setAvoidUnpavedRoads(v) }
    fun setAvoidNarrowRoads(v: Boolean)              = launch { repo.setAvoidNarrowRoads(v) }
    fun setAutoReroute(v: Boolean)                   = launch { repo.setAutoReroute(v) }
    fun setAutoAcceptFasterRoute(v: FasterRouteThreshold) = launch { repo.setAutoAcceptFasterRoute(v) }
    fun setAskBeforeMajorReroute(v: Boolean)         = launch { repo.setAskBeforeMajorReroute(v) }
    fun setAlternativesMode(v: AlternativesMode)     = launch { repo.setAlternativesMode(v) }
    fun setAlternativeCount(v: Int)                  = launch { repo.setAlternativeCount(v) }

    // Map appearance
    fun setMapTheme(v: MapTheme)                     = launch { repo.setMapTheme(v) }
    fun setMapPerspective(v: MapPerspective)         = launch { repo.setMapPerspective(v) }
    fun setHeadingMode(v: HeadingMode)               = launch { repo.setHeadingMode(v) }
    fun setShow3dBuildings(v: Boolean)               = launch { repo.setShow3dBuildings(v) }
    fun setShowTerrain(v: Boolean)                   = launch { repo.setShowTerrain(v) }
    fun setShowHillShading(v: Boolean)               = launch { repo.setShowHillShading(v) }
    fun setShowTrafficLayer(v: Boolean)              = launch { repo.setShowTrafficLayer(v) }
    fun setShowPoiLayer(v: Boolean)                  = launch { repo.setShowPoiLayer(v) }
    fun setShowGroupCarsOnMap(v: Boolean)            = launch { repo.setShowGroupCarsOnMap(v) }
    fun setMapDetailLevel(v: MapDetailLevel)         = launch { repo.setMapDetailLevel(v) }
    fun setNavMapTilt(v: Boolean)                    = launch { repo.setNavMapTilt(v) }
    fun setKeepScreenOn(v: Boolean)                  = launch { repo.setKeepScreenOn(v) }
    fun setAutoZoom(v: Boolean)                      = launch { repo.setAutoZoom(v) }
    fun setAutoZoomBySpeed(v: Boolean)               = launch { repo.setAutoZoomBySpeed(v) }
    fun setAutoZoomOnIntersections(v: Boolean)       = launch { repo.setAutoZoomOnIntersections(v) }
    fun setAutoZoomOnRoundabouts(v: Boolean)         = launch { repo.setAutoZoomOnRoundabouts(v) }
    fun setAutoZoomOnMotorwayExits(v: Boolean)       = launch { repo.setAutoZoomOnMotorwayExits(v) }
    fun setAutoZoomOnLaneGuidance(v: Boolean)        = launch { repo.setAutoZoomOnLaneGuidance(v) }

    // Safety
    fun setSafetyFeaturesEnabled(v: Boolean)         = launch { repo.setSafetyFeaturesEnabled(v) }
    fun setShowSpeedCameras(v: Boolean)              = launch { repo.setShowSpeedCameras(v) }
    fun setShowRedLightCameras(v: Boolean)           = launch { repo.setShowRedLightCameras(v) }
    fun setShowCombinedCameras(v: Boolean)           = launch { repo.setShowCombinedCameras(v) }
    fun setShowAverageSpeedZones(v: Boolean)         = launch { repo.setShowAverageSpeedZones(v) }
    fun setShowMobileEnforcement(v: Boolean)         = launch { repo.setShowMobileEnforcement(v) }
    fun setCameraAlertDistance(v: CameraAlertDistance) = launch { repo.setCameraAlertDistance(v) }
    fun setCameraAlertStyle(v: CameraAlertStyle)     = launch { repo.setCameraAlertStyle(v) }
    fun setShowCameraOnMap(v: Boolean)               = launch { repo.setShowCameraOnMap(v) }
    fun setShowCameraDistance(v: Boolean)            = launch { repo.setShowCameraDistance(v) }
    fun setShowCameraSpeedLimit(v: Boolean)          = launch { repo.setShowCameraSpeedLimit(v) }
    fun setShowSchoolZones(v: Boolean)               = launch { repo.setShowSchoolZones(v) }
    fun setShowRoadworksAlerts(v: Boolean)           = launch { repo.setShowRoadworksAlerts(v) }
    fun setShowAccidentAlerts(v: Boolean)            = launch { repo.setShowAccidentAlerts(v) }
    fun setShowWeatherWarnings(v: Boolean)           = launch { repo.setShowWeatherWarnings(v) }
    fun setShowSharpCurveWarnings(v: Boolean)        = launch { repo.setShowSharpCurveWarnings(v) }

    // Speed
    fun setShowCurrentSpeed(v: Boolean)              = launch { repo.setShowCurrentSpeed(v) }
    fun setShowSpeedLimit(v: Boolean)                = launch { repo.setShowSpeedLimit(v) }
    fun setSpeedWarningThreshold(v: SpeedWarningThreshold) = launch { repo.setSpeedWarningThreshold(v) }
    fun setSpeedWarningVisual(v: Boolean)            = launch { repo.setSpeedWarningVisual(v) }
    fun setSpeedWarningAudio(v: Boolean)             = launch { repo.setSpeedWarningAudio(v) }

    // Road Ahead
    fun setShowRoadAhead(v: Boolean)                 = launch { repo.setShowRoadAhead(v) }
    fun setRoadAheadMaxItems(v: Int)                 = launch { repo.setRoadAheadMaxItems(v) }
    fun setRoadAheadDistance(v: RoadAheadDistance)   = launch { repo.setRoadAheadDistance(v) }
    fun setRoadAheadShowCameras(v: Boolean)          = launch { repo.setRoadAheadShowCameras(v) }
    fun setRoadAheadShowTraffic(v: Boolean)          = launch { repo.setRoadAheadShowTraffic(v) }
    fun setRoadAheadShowAccidents(v: Boolean)        = launch { repo.setRoadAheadShowAccidents(v) }
    fun setRoadAheadShowRoadworks(v: Boolean)        = launch { repo.setRoadAheadShowRoadworks(v) }
    fun setRoadAheadShowWeather(v: Boolean)          = launch { repo.setRoadAheadShowWeather(v) }
    fun setRoadAheadShowFuel(v: Boolean)             = launch { repo.setRoadAheadShowFuel(v) }
    fun setRoadAheadShowRestAreas(v: Boolean)        = launch { repo.setRoadAheadShowRestAreas(v) }
    fun setRoadAheadShowBorders(v: Boolean)          = launch { repo.setRoadAheadShowBorders(v) }

    // Traffic
    fun setTrafficFeaturesEnabled(v: Boolean)        = launch { repo.setTrafficFeaturesEnabled(v) }
    fun setTrafficAwareEta(v: Boolean)               = launch { repo.setTrafficAwareEta(v) }
    fun setTrafficAwareRerouting(v: Boolean)         = launch { repo.setTrafficAwareRerouting(v) }
    fun setTrafficAlerts(v: Boolean)                 = launch { repo.setTrafficAlerts(v) }

    // Traffic signals
    fun setTrafficSignalIntelligence(v: Boolean)     = launch { repo.setTrafficSignalIntelligence(v) }
    fun setShowStaticTrafficLights(v: Boolean)       = launch { repo.setShowStaticTrafficLights(v) }
    fun setShowSignalTiming(v: Boolean)              = launch { repo.setShowSignalTiming(v) }
    fun setShowGlosa(v: Boolean)                     = launch { repo.setShowGlosa(v) }
    fun setShowSignalDistance(v: Boolean)            = launch { repo.setShowSignalDistance(v) }

    // Lane guidance
    fun setShowLaneGuidance(v: Boolean)              = launch { repo.setShowLaneGuidance(v) }
    fun setLaneGuidanceEarlyPreview(v: Boolean)      = launch { repo.setLaneGuidanceEarlyPreview(v) }
    fun setShowHighlightedRecommendedLanes(v: Boolean) = launch { repo.setShowHighlightedRecommendedLanes(v) }
    fun setShowLaneEndings(v: Boolean)               = launch { repo.setShowLaneEndings(v) }
    fun setShowLaneAdditions(v: Boolean)             = launch { repo.setShowLaneAdditions(v) }
    fun setShowExitOnlyLanes(v: Boolean)             = launch { repo.setShowExitOnlyLanes(v) }
    fun setShowSignboards(v: Boolean)                = launch { repo.setShowSignboards(v) }
    fun setShowExitNumbers(v: Boolean)               = launch { repo.setShowExitNumbers(v) }
    fun setShowRoadNumbers(v: Boolean)               = launch { repo.setShowRoadNumbers(v) }
    fun setShowDestinationNames(v: Boolean)          = launch { repo.setShowDestinationNames(v) }

    // Smart Stops
    fun setSmartStopsEnabled(v: Boolean)             = launch { repo.setSmartStopsEnabled(v) }
    fun setShowFuelButton(v: Boolean)                = launch { repo.setShowFuelButton(v) }
    fun setShowShopButton(v: Boolean)                = launch { repo.setShowShopButton(v) }
    fun setShowToiletButton(v: Boolean)              = launch { repo.setShowToiletButton(v) }
    fun setShowCoffeeButton(v: Boolean)              = launch { repo.setShowCoffeeButton(v) }
    fun setShowEvButton(v: Boolean)                  = launch { repo.setShowEvButton(v) }
    fun setSmartStopInstantResult(v: Boolean)        = launch { repo.setSmartStopInstantResult(v) }
    fun setSmartStopOnlyOpen(v: Boolean)             = launch { repo.setSmartStopOnlyOpen(v) }
    fun setSmartStopMaxDetourMinutes(v: Int)         = launch { repo.setSmartStopMaxDetourMinutes(v) }
    fun setSmartStopRequireParking(v: Boolean)       = launch { repo.setSmartStopRequireParking(v) }
    fun setSmartStopAvoidUTurn(v: Boolean)           = launch { repo.setSmartStopAvoidUTurn(v) }

    // Fuel
    fun setFuelType(v: FuelType)                     = launch { repo.setFuelType(v) }
    fun setFuelPreference(v: FuelPreference)         = launch { repo.setFuelPreference(v) }
    fun setFuelDetourLimit(v: FuelDetourLimit)       = launch { repo.setFuelDetourLimit(v) }
    fun setSupermarketPreference(v: SupermarketPreference) = launch { repo.setSupermarketPreference(v) }

    // Border
    fun setShowBorderAlerts(v: Boolean)              = launch { repo.setShowBorderAlerts(v) }
    fun setSmartBorderMode(v: Boolean)               = launch { repo.setSmartBorderMode(v) }
    fun setBorderShowSpeedLimits(v: Boolean)         = launch { repo.setBorderShowSpeedLimits(v) }
    fun setBorderShowVignette(v: Boolean)            = launch { repo.setBorderShowVignette(v) }
    fun setBorderShowTolls(v: Boolean)               = launch { repo.setBorderShowTolls(v) }
    fun setBorderShowRules(v: Boolean)               = launch { repo.setBorderShowRules(v) }

    // Road personality
    fun setShowRoadPersonality(v: Boolean)           = launch { repo.setShowRoadPersonality(v) }

    // Real-world feel
    fun setRealWorldFeelEnabled(v: Boolean)          = launch { repo.setRealWorldFeelEnabled(v) }
    fun setShowVisibleAircraft(v: Boolean)           = launch { repo.setShowVisibleAircraft(v) }
    fun setShowAirportApproach(v: Boolean)           = launch { repo.setShowAirportApproach(v) }
    fun setShowRailCrossingIntelligence(v: Boolean)  = launch { repo.setShowRailCrossingIntelligence(v) }
    fun setShowSkyAndLightReality(v: Boolean)        = launch { repo.setShowSkyAndLightReality(v) }
    fun setShowSunGlareWarning(v: Boolean)           = launch { repo.setShowSunGlareWarning(v) }
    fun setShowRoadsideLandmarks(v: Boolean)         = launch { repo.setShowRoadsideLandmarks(v) }
    fun setShowWaterFerryBridgeMoments(v: Boolean)   = launch { repo.setShowWaterFerryBridgeMoments(v) }
    fun setShowWildlifeRiskAtmosphere(v: Boolean)    = launch { repo.setShowWildlifeRiskAtmosphere(v) }
    fun setShowEventCrowdPulse(v: Boolean)           = launch { repo.setShowEventCrowdPulse(v) }
    fun setShowRoadFeelMode(v: Boolean)              = launch { repo.setShowRoadFeelMode(v) }
    fun setShowWindFlow(v: Boolean)                  = launch { repo.setShowWindFlow(v) }
    fun setShowFogDepthLayer(v: Boolean)             = launch { repo.setShowFogDepthLayer(v) }
    fun setShowStormCellEncounter(v: Boolean)        = launch { repo.setShowStormCellEncounter(v) }
    fun setShowAmbientRoutePulse(v: Boolean)         = launch { repo.setShowAmbientRoutePulse(v) }
    fun setShowEmergencyVehicleAwareness(v: Boolean) = launch { repo.setShowEmergencyVehicleAwareness(v) }
    fun setShowRoadSurfaceFeel(v: Boolean)           = launch { repo.setShowRoadSurfaceFeel(v) }
    fun setShowDestinationArrivalMood(v: Boolean)    = launch { repo.setShowDestinationArrivalMood(v) }
    fun setShowRealWeatherAhead(v: Boolean)          = launch { repo.setShowRealWeatherAhead(v) }
    fun setShowMoonNightSky(v: Boolean)              = launch { repo.setShowMoonNightSky(v) }
    fun setShowVisibleHazardScene(v: Boolean)        = launch { repo.setShowVisibleHazardScene(v) }

    // Voice
    fun setVoiceGuidanceMode(v: VoiceGuidanceMode)   = launch { repo.setVoiceGuidanceMode(v) }
    fun setVoiceIncludesStreetNames(v: Boolean)      = launch { repo.setVoiceIncludesStreetNames(v) }
    fun setVoiceIncludesRoadNumbers(v: Boolean)      = launch { repo.setVoiceIncludesRoadNumbers(v) }
    fun setVoiceTiming(v: VoiceTiming)               = launch { repo.setVoiceTiming(v) }

    // Group Drive
    fun setGroupDriveEnabled(v: Boolean)             = launch { repo.setGroupDriveEnabled(v) }
    fun setShowGroupPanel(v: Boolean)                = launch { repo.setShowGroupPanel(v) }
    fun setShareLocationWithGroup(v: Boolean)        = launch { repo.setShareLocationWithGroup(v) }
    fun setShareEtaWithGroup(v: Boolean)             = launch { repo.setShareEtaWithGroup(v) }
    fun setShareSpeedWithGroup(v: Boolean)           = launch { repo.setShareSpeedWithGroup(v) }
    fun setGroupGapThreshold(v: GroupGapThreshold)   = launch { repo.setGroupGapThreshold(v) }
    fun setGroupNotifyVehicleBehind(v: Boolean)      = launch { repo.setGroupNotifyVehicleBehind(v) }
    fun setGroupNotifyVehicleStopped(v: Boolean)     = launch { repo.setGroupNotifyVehicleStopped(v) }
    fun setGroupAutoAcceptLeaderFuel(v: Boolean)     = launch { repo.setGroupAutoAcceptLeaderFuel(v) }
    fun setGroupAutoAcceptLeaderShop(v: Boolean)     = launch { repo.setGroupAutoAcceptLeaderShop(v) }

    // Privacy
    fun setPrivacyPreset(v: PrivacyPreset)           = launch { repo.setPrivacyPreset(v) }
    fun setRecentSearchHistoryEnabled(v: Boolean)    = launch { repo.setRecentSearchHistoryEnabled(v) }
    fun setRecentDestinationHistoryEnabled(v: Boolean) = launch { repo.setRecentDestinationHistoryEnabled(v) }
    fun setTripHistoryEnabled(v: Boolean)            = launch { repo.setTripHistoryEnabled(v) }
    fun setTripHistoryRetention(v: TripHistoryRetention) = launch { repo.setTripHistoryRetention(v) }
    fun setAnalyticsEnabled(v: Boolean)              = launch { repo.setAnalyticsEnabled(v) }
    fun setCrashReportsEnabled(v: Boolean)           = launch { repo.setCrashReportsEnabled(v) }

    // Accessibility
    fun setLargeButtonsMode(v: Boolean)              = launch { repo.setLargeButtonsMode(v) }
    fun setHighContrastMode(v: Boolean)              = launch { repo.setHighContrastMode(v) }
    fun setReduceMotion(v: Boolean)                  = launch { repo.setReduceMotion(v) }

    // Units
    fun setUnits(v: DistanceUnits)                   = launch { repo.setUnits(v) }

    // Reset
    fun resetToDefaults()                            = launch { repo.resetToDefaults() }
}
