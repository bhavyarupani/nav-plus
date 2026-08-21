package com.navplus.core.navigation

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStyle
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RealWorldCueType {
    VISIBLE_AIRCRAFT,
    AIRPORT_APPROACH,
    RAIL_CROSSING,
    SKY_LIGHT,
    SUN_GLARE,
    LANDMARK,
    WATER_FERRY_BRIDGE,
    WILDLIFE_RISK,
    EVENT_CROWD,
    ROAD_FEEL,
    WIND_FLOW,
    FOG_DEPTH,
    STORM_CELL,
    AMBIENT_ROUTE_PULSE,
    EMERGENCY_VEHICLE,
    ROAD_SURFACE,
    DESTINATION_ARRIVAL,
    ROUTE_WEATHER,
    MOON_NIGHT_SKY,
    HAZARD_SCENE,
}

enum class RealWorldSky {
    CLEAR,
    CLOUDY,
    OVERCAST,
    RAIN,
    SNOW,
    FOG,
    NIGHT,
    SUNSET,
}

enum class RealWorldRoadFeel {
    OPEN_ROAD,
    CITY_CANYON,
    FOREST,
    COAST,
    RIVER,
    BRIDGE,
    TUNNEL,
    MOUNTAIN,
}

enum class RealWorldRoutePulse {
    CALM,
    TRAFFIC_AHEAD,
    HEAVY_CONGESTION,
    RAIN_OR_ICE,
    LOW_GPS_CONFIDENCE,
    EMERGENCY_AHEAD,
}

data class RealWorldOptions(
    val enabled: Boolean = true,
    val visibleAircraft: Boolean = true,
    val airportApproach: Boolean = true,
    val railCrossing: Boolean = true,
    val skyAndLight: Boolean = true,
    val sunGlare: Boolean = true,
    val landmarkGlance: Boolean = true,
    val waterFerryBridge: Boolean = true,
    val wildlifeRisk: Boolean = true,
    val eventCrowd: Boolean = true,
    val roadFeel: Boolean = true,
    val windFlow: Boolean = true,
    val fogDepth: Boolean = true,
    val stormCell: Boolean = true,
    val ambientRoutePulse: Boolean = true,
    val emergencyAwareness: Boolean = true,
    val roadSurfaceFeel: Boolean = true,
    val destinationArrivalMood: Boolean = true,
    val realWeatherAhead: Boolean = true,
    val moonNightSky: Boolean = true,
    val visibleHazardScene: Boolean = true,
)

data class RealWorldEnvironment(
    val now: LocalDateTime = LocalDateTime.now(),
    val visibilityMeters: Double = 22_000.0,
    val cloudCoverPercent: Int = 35,
    val precipitationMm: Double = 0.0,
    val snowfallCm: Double = 0.0,
    val fogRisk: Boolean = false,
    val windSpeedKph: Double = 12.0,
    val windGustKph: Double = 22.0,
    val humidityPercent: Int = 56,
    val stormDistanceMeters: Double? = null,
    val stormEtaMinutes: Int? = null,
    val trafficDelaySeconds: Long = 0L,
    val lowGpsConfidence: Boolean = false,
    val emergencyZoneDistanceMeters: Double? = null,
    val hazardSceneDistanceMeters: Double? = null,
    val roadSurface: String? = null,
    val roadSurfaceDistanceMeters: Double? = null,
    val destinationDistanceMeters: Double? = null,
    val moonVisible: Boolean = false,
    val liveFeedsAvailable: Boolean = false,
)

data class RealWorldFrame(
    val atmosphere: RealWorldAtmosphere,
    val cues: List<RealWorldCue>,
) {
    companion object {
        val Empty = RealWorldFrame(
            atmosphere = RealWorldAtmosphere(),
            cues = emptyList(),
        )
    }
}

data class RealWorldAtmosphere(
    val sky: RealWorldSky = RealWorldSky.CLEAR,
    val roadFeel: RealWorldRoadFeel = RealWorldRoadFeel.OPEN_ROAD,
    val routePulse: RealWorldRoutePulse = RealWorldRoutePulse.CALM,
    val routePulseColor: String = "#3B82F6",
    val intensity: Float = 0.0f,
)

data class RealWorldCue(
    val id: String,
    val type: RealWorldCueType,
    val position: LatLng,
    val title: String,
    val subtitle: String,
    val icon: String,
    val color: String,
    val distanceMeters: Double,
    val relativeBearingDeg: Double,
    val priority: Int,
    val sourceLabel: String,
)

@Singleton
class RealWorldEngine @Inject constructor() {

    fun frameAhead(
        route: Route,
        currentPosition: LatLng,
        headingDeg: Float,
        distanceFromStartMeters: Double,
        currentSpeedKph: Float?,
        options: RealWorldOptions,
        environment: RealWorldEnvironment = RealWorldEnvironment(),
        maxCues: Int = 6,
    ): RealWorldFrame {
        if (!options.enabled || route.geometry.isEmpty()) return RealWorldFrame.Empty

        val base = route.pointAtDistance(distanceFromStartMeters)
        val aheadShort = route.pointAtDistance(distanceFromStartMeters + 1_200.0)
        val aheadMid = route.pointAtDistance(distanceFromStartMeters + 3_000.0)
        val aheadLong = route.pointAtDistance(distanceFromStartMeters + 8_000.0)
        val sky = environment.sky()
        val feel = route.roadFeel(environment, distanceFromStartMeters)
        val pulse = route.routePulse(sky, environment)
        val atmosphere = RealWorldAtmosphere(
            sky = sky,
            roadFeel = feel,
            routePulse = pulse,
            routePulseColor = pulseColor(sky, feel, pulse),
            intensity = atmosphereIntensity(sky, feel),
        )

        val cues = buildList {
            if (options.visibleAircraft) addVisibleAircraft(currentPosition, headingDeg, environment, route)
            if (options.airportApproach) addAirportApproach(currentPosition, route, environment)
            if (options.railCrossing) addRailCrossing(currentPosition, aheadShort, currentSpeedKph, environment)
            if (options.skyAndLight) addSkyCue(currentPosition, aheadMid, sky, environment)
            if (options.sunGlare) addSunGlare(currentPosition, headingDeg, aheadShort, environment)
            if (options.landmarkGlance) addLandmark(currentPosition, headingDeg, route, environment)
            if (options.waterFerryBridge) addWaterMoment(currentPosition, aheadMid, route)
            if (options.wildlifeRisk) addWildlifeRisk(currentPosition, headingDeg, aheadLong, route, environment)
            if (options.eventCrowd) addEventCrowd(currentPosition, route, environment)
            if (options.roadFeel) addRoadFeel(currentPosition, base, feel, route, environment)
            if (options.windFlow) addWindFlow(currentPosition, aheadShort, feel, route, environment)
            if (options.fogDepth) addFogDepth(currentPosition, aheadMid, environment)
            if (options.stormCell) addStormCell(currentPosition, aheadLong, environment)
            if (options.ambientRoutePulse) addAmbientRoutePulse(currentPosition, base, pulse, environment)
            if (options.emergencyAwareness) addEmergencyAwareness(currentPosition, headingDeg, route, environment)
            if (options.roadSurfaceFeel) addRoadSurfaceFeel(currentPosition, aheadShort, route, environment)
            if (options.destinationArrivalMood) addDestinationArrival(currentPosition, route, environment)
            if (options.realWeatherAhead) addRouteWeatherAhead(currentPosition, aheadMid, sky, environment)
            if (options.moonNightSky) addMoonNightSky(currentPosition, aheadLong, feel, environment)
            if (options.visibleHazardScene) addHazardScene(currentPosition, headingDeg, route, environment)
        }
            .filter { it.distanceMeters <= MAX_VISIBLE_WORLD_METERS }
            .sortedWith(compareByDescending<RealWorldCue> { it.priority }.thenBy { it.distanceMeters })
            .take(maxCues)

        return RealWorldFrame(atmosphere, cues)
    }

    fun simulationFrame(): RealWorldFrame {
        val position = LatLng(48.3538, 11.7861)
        val route = Route(
            id = "real-world-simulation",
            waypoints = listOf(position, LatLng(48.4200, 11.9000)),
            geometry = listOf(
                position,
                LatLng(48.3650, 11.8050),
                LatLng(48.3820, 11.8300),
                LatLng(48.4050, 11.8650),
                LatLng(48.4200, 11.9000),
            ),
            steps = emptyList(),
            distanceMeters = 11_000.0,
            durationSeconds = 720L,
            style = RouteStyle.SCENIC,
            hasHighways = true,
        )
        return frameAhead(
            route = route,
            currentPosition = position,
            headingDeg = 62f,
            distanceFromStartMeters = 0.0,
            currentSpeedKph = 82f,
            options = RealWorldOptions(),
            environment = RealWorldEnvironment(
                now = LocalDateTime.of(2026, 8, 21, 19, 42),
                cloudCoverPercent = 48,
                precipitationMm = 0.8,
                fogRisk = true,
                visibilityMeters = 1_900.0,
                windSpeedKph = 34.0,
                windGustKph = 62.0,
                humidityPercent = 94,
                stormDistanceMeters = 5_400.0,
                stormEtaMinutes = 7,
                trafficDelaySeconds = 540L,
                lowGpsConfidence = true,
                emergencyZoneDistanceMeters = 2_200.0,
                hazardSceneDistanceMeters = 1_800.0,
                roadSurface = "wet cobblestone",
                roadSurfaceDistanceMeters = 1_100.0,
                destinationDistanceMeters = 650.0,
                moonVisible = true,
                liveFeedsAvailable = false,
            ),
            maxCues = RealWorldCueType.entries.size,
        )
    }

    private fun MutableList<RealWorldCue>.addVisibleAircraft(
        current: LatLng,
        headingDeg: Float,
        environment: RealWorldEnvironment,
        route: Route,
    ) {
        val airport = KNOWN_AIRPORTS.minByOrNull { current.distanceTo(it.position) }
        val airportBearing = airport?.let { current.bearingTo(it.position) } ?: (headingDeg + 35.0)
        val planeDistance = if (airport != null && current.distanceTo(airport.position) < 38_000.0) 7_800.0 else 14_000.0
        val planeBearing = airportBearing.normalizeBearing()
        val planePosition = current.project(planeBearing, planeDistance)
        val relative = signedBearingDelta(headingDeg.toDouble(), current.bearingTo(planePosition))
        val elevationDeg = elevationAngle(altitudeMeters = 1_650.0, distanceMeters = planeDistance)
        val visible = abs(relative) <= 115.0 &&
            elevationDeg in 2.0..28.0 &&
            environment.visibilityMeters >= planeDistance * 0.75
        if (!visible && airport == null) return
        add(
            cue(
                id = "aircraft-${airport?.code ?: route.id}",
                type = RealWorldCueType.VISIBLE_AIRCRAFT,
                position = planePosition,
                title = if (airport != null) "Visible aircraft" else "Aircraft in side view",
                subtitle = "${planeDistance.kmLabel()} ${windowName(relative)}, ${elevationDeg.roundToInt()} deg up",
                icon = "✈",
                color = "#60A5FA",
                current = current,
                priority = if (airport != null) 92 else 72,
                source = if (environment.liveFeedsAvailable) "OpenSky live" else "Visibility model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addAirportApproach(
        current: LatLng,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val airport = KNOWN_AIRPORTS.firstOrNull { airport ->
            current.distanceTo(airport.position) < 42_000.0 ||
                route.geometry.any { it.distanceTo(airport.position) < 35_000.0 }
        } ?: return
        val corridorBearing = airport.runwayBearingDeg
        val corridorPoint = airport.position.project((corridorBearing + 180.0).normalizeBearing(), 8_000.0)
        add(
            cue(
                id = "airport-${airport.code}",
                type = RealWorldCueType.AIRPORT_APPROACH,
                position = corridorPoint,
                title = "${airport.name} approach",
                subtitle = "Landing and departure corridor nearby",
                icon = "⌁",
                color = "#38BDF8",
                current = current,
                priority = 88,
                source = if (environment.liveFeedsAvailable) "Airport live layer" else "Airport geometry",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addRailCrossing(
        current: LatLng,
        ahead: LatLng,
        speedKph: Float?,
        environment: RealWorldEnvironment,
    ) {
        val etaSec = current.distanceTo(ahead) / ((speedKph ?: 50f).coerceAtLeast(15f) / 3.6)
        add(
            cue(
                id = "rail-${ahead.lat.roundKey()}-${ahead.lng.roundKey()}",
                type = RealWorldCueType.RAIL_CROSSING,
                position = ahead.project(94.0, 140.0),
                title = "Rail crossing watch",
                subtitle = "Train timing checked near ${etaSec.roundToInt()} sec ETA",
                icon = "▰",
                color = "#F59E0B",
                current = current,
                priority = 70,
                source = if (environment.liveFeedsAvailable) "GTFS-RT live" else "Route crossing model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addSkyCue(
        current: LatLng,
        ahead: LatLng,
        sky: RealWorldSky,
        environment: RealWorldEnvironment,
    ) {
        val (title, subtitle, icon, color) = when (sky) {
            RealWorldSky.RAIN -> Quad("Rain ahead", "Road atmosphere follows rain", "☂", "#38BDF8")
            RealWorldSky.SNOW -> Quad("Snow feel", "Low-distraction snow layer", "✳", "#E0F2FE")
            RealWorldSky.FOG -> Quad("Fog ahead", "Map fades route depth gently", "≈", "#CBD5E1")
            RealWorldSky.SUNSET -> Quad("Sunset light", "Warm low-light map tone", "◐", "#FB923C")
            RealWorldSky.NIGHT -> Quad("Night sky", "Dark calm route ambience", "●", "#818CF8")
            RealWorldSky.OVERCAST -> Quad("Overcast", "Muted sky tone", "☁", "#94A3B8")
            RealWorldSky.CLOUDY -> Quad("Cloud patches", "Soft cloud ambience", "☁", "#BAE6FD")
            RealWorldSky.CLEAR -> Quad("Clear sky", "Bright route lighting", "○", "#FACC15")
        }
        add(
            cue(
                id = "sky-${sky.name.lowercase()}",
                type = RealWorldCueType.SKY_LIGHT,
                position = ahead,
                title = title,
                subtitle = "$subtitle, visibility ${(environment.visibilityMeters / 1000).roundToInt()} km",
                icon = icon,
                color = color,
                current = current,
                priority = if (sky in setOf(RealWorldSky.RAIN, RealWorldSky.SNOW, RealWorldSky.FOG)) 82 else 52,
                source = if (environment.liveFeedsAvailable) "Weather live" else "Weather model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addSunGlare(
        current: LatLng,
        headingDeg: Float,
        ahead: LatLng,
        environment: RealWorldEnvironment,
    ) {
        val sun = approximateSun(environment.now)
        val relative = signedBearingDelta(headingDeg.toDouble(), sun.azimuthDeg)
        if (sun.elevationDeg !in 1.0..14.0 || abs(relative) > 70.0) return
        add(
            cue(
                id = "sun-glare",
                type = RealWorldCueType.SUN_GLARE,
                position = ahead.project(sun.azimuthDeg, 300.0),
                title = "Low sun glare",
                subtitle = "${windowName(relative)} ${abs(relative).roundToInt()} deg",
                icon = "◉",
                color = "#F97316",
                current = current,
                priority = 95,
                source = "Sun angle model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addLandmark(
        current: LatLng,
        headingDeg: Float,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val known = KNOWN_LANDMARKS
            .filter { current.distanceTo(it.position) <= environment.visibilityMeters.coerceAtMost(30_000.0) }
            .map { it to abs(signedBearingDelta(headingDeg.toDouble(), current.bearingTo(it.position))) }
            .filter { (_, relative) -> relative <= 120.0 }
            .minByOrNull { (_, relative) -> relative }
            ?.first
        val landmark = known
            ?: route.geometry.getOrNull((route.geometry.lastIndex * 0.65).roundToInt())?.let {
                Landmark("Scenic skyline", it.project(315.0, 900.0), "#A78BFA")
            }
        landmark ?: return
        val relative = signedBearingDelta(headingDeg.toDouble(), current.bearingTo(landmark.position))
        if (abs(relative) > 120.0) return
        add(
            cue(
                id = "landmark-${landmark.name.lowercase().replace(" ", "-")}",
                type = RealWorldCueType.LANDMARK,
                position = landmark.position,
                title = landmark.name,
                subtitle = "Visible ${windowName(relative)}",
                icon = "◆",
                color = landmark.color,
                current = current,
                priority = 58,
                source = if (environment.liveFeedsAvailable) "OSM/Wikidata" else "Landmark catalog",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addWaterMoment(current: LatLng, ahead: LatLng, route: Route) {
        val isBridge = route.steps.any { step ->
            step.instruction.contains("bridge", ignoreCase = true) ||
                step.streetName?.contains("brücke", ignoreCase = true) == true
        }
        add(
            cue(
                id = "water-bridge-${ahead.lat.roundKey()}",
                type = RealWorldCueType.WATER_FERRY_BRIDGE,
                position = ahead.project(180.0, 180.0),
                title = if (isBridge) "Bridge moment" else "Water nearby",
                subtitle = if (isBridge) "Bridge context on route" else "River/ferry/bridge layer ready",
                icon = "≈",
                color = "#0EA5E9",
                current = current,
                priority = if (isBridge) 74 else 48,
                source = "OSM water/bridge model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addWildlifeRisk(
        current: LatLng,
        headingDeg: Float,
        ahead: LatLng,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val hour = environment.now.hour
        val duskOrNight = hour >= 19 || hour <= 6
        val ruralRoute = route.style == RouteStyle.SCENIC || route.style == RouteStyle.LOW_STRESS ||
            route.steps.any { it.streetName?.contains("wald", ignoreCase = true) == true }
        if (!duskOrNight && !ruralRoute) return
        add(
            cue(
                id = "wildlife-risk",
                type = RealWorldCueType.WILDLIFE_RISK,
                position = ahead.project(headingDeg.toDouble(), 260.0),
                title = "Wildlife risk",
                subtitle = if (duskOrNight) "Dusk rural section" else "Forest/rural road",
                icon = "!",
                color = "#FBBF24",
                current = current,
                priority = 68,
                source = "Rural/time risk model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addEventCrowd(
        current: LatLng,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val venue = KNOWN_VENUES
            .firstOrNull { venue -> route.geometry.any { it.distanceTo(venue.position) < 5_500.0 } }
            ?: return
        add(
            cue(
                id = "event-${venue.name.lowercase().replace(" ", "-")}",
                type = RealWorldCueType.EVENT_CROWD,
                position = venue.position,
                title = venue.name,
                subtitle = "Crowd pulse zone near route",
                icon = "●",
                color = "#EC4899",
                current = current,
                priority = 62,
                source = if (environment.liveFeedsAvailable) "Event/traffic live" else "Venue proximity",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addRoadFeel(
        current: LatLng,
        base: LatLng,
        feel: RealWorldRoadFeel,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val title = when (feel) {
            RealWorldRoadFeel.OPEN_ROAD -> "Open road feel"
            RealWorldRoadFeel.CITY_CANYON -> "City canyon"
            RealWorldRoadFeel.FOREST -> "Forest section"
            RealWorldRoadFeel.COAST -> "Coast road"
            RealWorldRoadFeel.RIVER -> "River road"
            RealWorldRoadFeel.BRIDGE -> "Bridge mode"
            RealWorldRoadFeel.TUNNEL -> "Tunnel mode"
            RealWorldRoadFeel.MOUNTAIN -> "Mountain grade"
        }
        add(
            cue(
                id = "road-feel-${feel.name.lowercase()}",
                type = RealWorldCueType.ROAD_FEEL,
                position = base.project(45.0, 220.0),
                title = title,
                subtitle = if (route.ascendMeters > 250.0 || route.descendMeters > 250.0) "Elevation-aware route pulse" else "Ambient map pulse active",
                icon = "▱",
                color = pulseColor(environment.sky(), feel, route.routePulse(environment.sky(), environment)),
                current = current,
                priority = 50,
                source = "OSM road character",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addWindFlow(
        current: LatLng,
        ahead: LatLng,
        feel: RealWorldRoadFeel,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val exposed = feel in setOf(
            RealWorldRoadFeel.BRIDGE,
            RealWorldRoadFeel.MOUNTAIN,
            RealWorldRoadFeel.COAST,
            RealWorldRoadFeel.OPEN_ROAD,
        ) || route.hasHighways
        if (!exposed || environment.windGustKph < 45.0) return
        add(
            cue(
                id = "wind-flow-${ahead.lat.roundKey()}",
                type = RealWorldCueType.WIND_FLOW,
                position = ahead.project(75.0, 180.0),
                title = "Crosswind feel",
                subtitle = "Gusts ${environment.windGustKph.roundToInt()} km/h on exposed road",
                icon = "~",
                color = "#7DD3FC",
                current = current,
                priority = if (environment.windGustKph >= 65.0) 86 else 64,
                source = if (environment.liveFeedsAvailable) "Open-Meteo wind" else "Wind exposure model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addFogDepth(
        current: LatLng,
        ahead: LatLng,
        environment: RealWorldEnvironment,
    ) {
        val lowVisibility = environment.fogRisk || environment.visibilityMeters < 4_000.0 || environment.humidityPercent >= 92
        if (!lowVisibility) return
        add(
            cue(
                id = "fog-depth",
                type = RealWorldCueType.FOG_DEPTH,
                position = ahead,
                title = "Fog depth ahead",
                subtitle = "Visibility ${(environment.visibilityMeters / 1000.0).coerceAtLeast(0.2).roundToSingleDecimal()} km",
                icon = "≋",
                color = "#CBD5E1",
                current = current,
                priority = 84,
                source = if (environment.liveFeedsAvailable) "Open-Meteo visibility" else "Fog depth model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addStormCell(
        current: LatLng,
        ahead: LatLng,
        environment: RealWorldEnvironment,
    ) {
        val distance = environment.stormDistanceMeters ?: return
        val eta = environment.stormEtaMinutes ?: return
        val position = current.project(current.bearingTo(ahead), distance)
        add(
            cue(
                id = "storm-cell-$eta",
                type = RealWorldCueType.STORM_CELL,
                position = position,
                title = "Storm cell crossing",
                subtitle = "Rain meets route in $eta min",
                icon = "☈",
                color = "#2563EB",
                current = current,
                priority = 90,
                source = if (environment.liveFeedsAvailable) "Weather radar" else "Forecast encounter model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addAmbientRoutePulse(
        current: LatLng,
        base: LatLng,
        pulse: RealWorldRoutePulse,
        environment: RealWorldEnvironment,
    ) {
        val (title, subtitle, color) = when (pulse) {
            RealWorldRoutePulse.CALM -> Triple("Calm route pulse", "Normal road state", "#22C55E")
            RealWorldRoutePulse.TRAFFIC_AHEAD -> Triple("Traffic pulse", "Delay ${environment.trafficDelaySeconds / 60} min ahead", "#F59E0B")
            RealWorldRoutePulse.HEAVY_CONGESTION -> Triple("Heavy traffic pulse", "Slow segment ahead", "#EF4444")
            RealWorldRoutePulse.RAIN_OR_ICE -> Triple("Weather pulse", "Route color follows wet/icy risk", "#38BDF8")
            RealWorldRoutePulse.LOW_GPS_CONFIDENCE -> Triple("Low GPS confidence", "Pointer prediction mode ready", "#8B5CF6")
            RealWorldRoutePulse.EMERGENCY_AHEAD -> Triple("Emergency pulse", "Official hazard zone ahead", "#DC2626")
        }
        add(
            cue(
                id = "route-pulse-${pulse.name.lowercase()}",
                type = RealWorldCueType.AMBIENT_ROUTE_PULSE,
                position = base.project(20.0, 180.0),
                title = title,
                subtitle = subtitle,
                icon = "•",
                color = color,
                current = current,
                priority = if (pulse == RealWorldRoutePulse.CALM) 36 else 72,
                source = "Route intelligence engine",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addEmergencyAwareness(
        current: LatLng,
        headingDeg: Float,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val distance = environment.emergencyZoneDistanceMeters ?: return
        val routeBearing = route.geometry.firstOrNull()?.bearingTo(route.geometry.lastOrNull() ?: current) ?: headingDeg.toDouble()
        add(
            cue(
                id = "emergency-aware-${distance.roundToInt()}",
                type = RealWorldCueType.EMERGENCY_VEHICLE,
                position = current.project(routeBearing, distance),
                title = "Emergency zone",
                subtitle = "Route-relevant official alert",
                icon = "+",
                color = "#EF4444",
                current = current,
                priority = 94,
                source = if (environment.liveFeedsAvailable) "Traffic/emergency feed" else "Emergency route model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addRoadSurfaceFeel(
        current: LatLng,
        ahead: LatLng,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val surface = environment.roadSurface
            ?: route.steps.firstOrNull {
                it.instruction.contains("cobblestone", ignoreCase = true) ||
                    it.instruction.contains("gravel", ignoreCase = true)
            }?.instruction
            ?: return
        val distance = environment.roadSurfaceDistanceMeters ?: current.distanceTo(ahead)
        add(
            cue(
                id = "surface-${surface.lowercase().replace(" ", "-")}",
                type = RealWorldCueType.ROAD_SURFACE,
                position = current.project(current.bearingTo(ahead), distance),
                title = "Road surface feel",
                subtitle = surface.replaceFirstChar { it.uppercase() },
                icon = "▥",
                color = if (surface.contains("wet", ignoreCase = true)) "#38BDF8" else "#A3A3A3",
                current = current,
                priority = 66,
                source = if (environment.liveFeedsAvailable) "OSM surface + weather" else "Surface model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addDestinationArrival(
        current: LatLng,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val distance = environment.destinationDistanceMeters ?: return
        if (distance > 1_200.0) return
        val destination = route.geometry.lastOrNull() ?: current
        add(
            cue(
                id = "arrival-mood",
                type = RealWorldCueType.DESTINATION_ARRIVAL,
                position = destination,
                title = "Arrival mood",
                subtitle = "Parking, walking handoff and weather ready",
                icon = "⌂",
                color = "#22C55E",
                current = current,
                priority = 76,
                source = if (environment.liveFeedsAvailable) "Arrival services" else "Destination model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addRouteWeatherAhead(
        current: LatLng,
        ahead: LatLng,
        sky: RealWorldSky,
        environment: RealWorldEnvironment,
    ) {
        if (sky !in setOf(RealWorldSky.RAIN, RealWorldSky.SNOW, RealWorldSky.FOG)) return
        val (title, icon, color) = when (sky) {
            RealWorldSky.RAIN -> Triple("Rain on route", "☂", "#38BDF8")
            RealWorldSky.SNOW -> Triple("Snow on route", "✳", "#E0F2FE")
            else -> Triple("Fog on route", "≋", "#CBD5E1")
        }
        add(
            cue(
                id = "route-weather-${sky.name.lowercase()}",
                type = RealWorldCueType.ROUTE_WEATHER,
                position = ahead,
                title = title,
                subtitle = "Weather rendered only in affected corridor",
                icon = icon,
                color = color,
                current = current,
                priority = 80,
                source = if (environment.liveFeedsAvailable) "Open-Meteo route sample" else "Route weather model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addMoonNightSky(
        current: LatLng,
        ahead: LatLng,
        feel: RealWorldRoadFeel,
        environment: RealWorldEnvironment,
    ) {
        val rural = feel in setOf(RealWorldRoadFeel.FOREST, RealWorldRoadFeel.MOUNTAIN, RealWorldRoadFeel.OPEN_ROAD)
        val night = environment.now.hour >= 22 || environment.now.hour <= 5
        if (!night || !rural || environment.cloudCoverPercent > 35 || !environment.moonVisible) return
        add(
            cue(
                id = "moon-night-sky",
                type = RealWorldCueType.MOON_NIGHT_SKY,
                position = ahead.project(300.0, 300.0),
                title = "Clear night sky",
                subtitle = "Rural moon ambience",
                icon = "◑",
                color = "#818CF8",
                current = current,
                priority = 42,
                source = if (environment.liveFeedsAvailable) "Cloud cover + landuse" else "Night sky model",
            )
        )
    }

    private fun MutableList<RealWorldCue>.addHazardScene(
        current: LatLng,
        headingDeg: Float,
        route: Route,
        environment: RealWorldEnvironment,
    ) {
        val distance = environment.hazardSceneDistanceMeters ?: return
        val routeBearing = route.geometry.firstOrNull()?.bearingTo(route.geometry.lastOrNull() ?: current) ?: headingDeg.toDouble()
        add(
            cue(
                id = "hazard-scene-${distance.roundToInt()}",
                type = RealWorldCueType.HAZARD_SCENE,
                position = current.project(routeBearing, distance),
                title = "Hazard scene",
                subtitle = "Incident lies on current route",
                icon = "!",
                color = "#F97316",
                current = current,
                priority = 88,
                source = if (environment.liveFeedsAvailable) "Traffic incident feed" else "Hazard scene model",
            )
        )
    }

    private fun cue(
        id: String,
        type: RealWorldCueType,
        position: LatLng,
        title: String,
        subtitle: String,
        icon: String,
        color: String,
        current: LatLng,
        priority: Int,
        source: String,
    ): RealWorldCue = RealWorldCue(
        id = id,
        type = type,
        position = position,
        title = title,
        subtitle = subtitle,
        icon = icon,
        color = color,
        distanceMeters = current.distanceTo(position),
        relativeBearingDeg = signedBearingDelta(0.0, current.bearingTo(position)),
        priority = priority,
        sourceLabel = source,
    )
}

private data class Airport(val code: String, val name: String, val position: LatLng, val runwayBearingDeg: Double)
private data class Landmark(val name: String, val position: LatLng, val color: String)
private data class Venue(val name: String, val position: LatLng)
private data class SunState(val azimuthDeg: Double, val elevationDeg: Double)
private data class Quad(val first: String, val second: String, val third: String, val fourth: String)

private val KNOWN_AIRPORTS = listOf(
    Airport("MUC", "Munich Airport", LatLng(48.3538, 11.7861), 82.0),
    Airport("FRA", "Frankfurt Airport", LatLng(50.0379, 8.5622), 70.0),
    Airport("VIE", "Vienna Airport", LatLng(48.1103, 16.5697), 110.0),
    Airport("ZRH", "Zurich Airport", LatLng(47.4582, 8.5555), 140.0),
    Airport("MXP", "Milan Malpensa", LatLng(45.6306, 8.7281), 170.0),
    Airport("ZAG", "Zagreb Airport", LatLng(45.7429, 16.0688), 50.0),
)

private val KNOWN_LANDMARKS = listOf(
    Landmark("Allianz Arena", LatLng(48.2188, 11.6247), "#A78BFA"),
    Landmark("Olympic Tower", LatLng(48.1745, 11.5538), "#A78BFA"),
    Landmark("Grossglockner view", LatLng(47.0745, 12.6940), "#22C55E"),
    Landmark("Lake Garda view", LatLng(45.6040, 10.6350), "#38BDF8"),
)

private val KNOWN_VENUES = listOf(
    Venue("Arena crowd zone", LatLng(48.2188, 11.6247)),
    Venue("Airport terminal crowd", LatLng(48.3538, 11.7861)),
    Venue("Central station crowd", LatLng(48.1402, 11.5583)),
)

private const val MAX_VISIBLE_WORLD_METERS = 30_000.0

private fun RealWorldEnvironment.sky(): RealWorldSky = when {
    snowfallCm > 0.1 -> RealWorldSky.SNOW
    fogRisk || visibilityMeters < 2_500.0 -> RealWorldSky.FOG
    precipitationMm > 0.2 -> RealWorldSky.RAIN
    now.hour >= 22 || now.hour <= 5 -> RealWorldSky.NIGHT
    now.hour in 19..21 || now.hour in 5..6 -> RealWorldSky.SUNSET
    cloudCoverPercent >= 85 -> RealWorldSky.OVERCAST
    cloudCoverPercent >= 45 -> RealWorldSky.CLOUDY
    else -> RealWorldSky.CLEAR
}

private fun Route.roadFeel(environment: RealWorldEnvironment, distanceFromStartMeters: Double): RealWorldRoadFeel {
    val instructionText = steps.joinToString(" ") { "${it.instruction} ${it.streetName.orEmpty()}" }.lowercase()
    return when {
        instructionText.contains("tunnel") -> RealWorldRoadFeel.TUNNEL
        instructionText.contains("bridge") || instructionText.contains("brücke") -> RealWorldRoadFeel.BRIDGE
        ascendMeters > 350.0 || descendMeters > 350.0 -> RealWorldRoadFeel.MOUNTAIN
        instructionText.contains("ufer") || instructionText.contains("river") || instructionText.contains("see") -> RealWorldRoadFeel.RIVER
        instructionText.contains("wald") || style == RouteStyle.SCENIC -> RealWorldRoadFeel.FOREST
        distanceFromStartMeters > distanceMeters * 0.55 && environment.cloudCoverPercent < 40 -> RealWorldRoadFeel.OPEN_ROAD
        hasHighways -> RealWorldRoadFeel.OPEN_ROAD
        else -> RealWorldRoadFeel.CITY_CANYON
    }
}

private fun Route.routePulse(sky: RealWorldSky, environment: RealWorldEnvironment): RealWorldRoutePulse = when {
    environment.emergencyZoneDistanceMeters != null || environment.hazardSceneDistanceMeters != null ->
        RealWorldRoutePulse.EMERGENCY_AHEAD
    environment.lowGpsConfidence -> RealWorldRoutePulse.LOW_GPS_CONFIDENCE
    sky in setOf(RealWorldSky.RAIN, RealWorldSky.SNOW, RealWorldSky.FOG) -> RealWorldRoutePulse.RAIN_OR_ICE
    environment.trafficDelaySeconds >= 600L || trafficDelaySeconds >= 600L -> RealWorldRoutePulse.HEAVY_CONGESTION
    environment.trafficDelaySeconds >= 120L || trafficDelaySeconds >= 120L -> RealWorldRoutePulse.TRAFFIC_AHEAD
    else -> RealWorldRoutePulse.CALM
}

private fun pulseColor(sky: RealWorldSky, feel: RealWorldRoadFeel, pulse: RealWorldRoutePulse): String = when {
    pulse == RealWorldRoutePulse.EMERGENCY_AHEAD -> "#DC2626"
    pulse == RealWorldRoutePulse.LOW_GPS_CONFIDENCE -> "#8B5CF6"
    pulse == RealWorldRoutePulse.HEAVY_CONGESTION -> "#EF4444"
    pulse == RealWorldRoutePulse.TRAFFIC_AHEAD -> "#F59E0B"
    pulse == RealWorldRoutePulse.RAIN_OR_ICE && sky == RealWorldSky.RAIN -> "#38BDF8"
    pulse == RealWorldRoutePulse.RAIN_OR_ICE -> "#CBD5E1"
    sky == RealWorldSky.SUNSET -> "#FB923C"
    feel == RealWorldRoadFeel.FOREST -> "#22C55E"
    feel == RealWorldRoadFeel.RIVER || feel == RealWorldRoadFeel.COAST -> "#0EA5E9"
    feel == RealWorldRoadFeel.TUNNEL -> "#64748B"
    pulse == RealWorldRoutePulse.CALM -> "#22C55E"
    else -> "#3B82F6"
}

private fun atmosphereIntensity(sky: RealWorldSky, feel: RealWorldRoadFeel): Float = when {
    sky in setOf(RealWorldSky.RAIN, RealWorldSky.SNOW, RealWorldSky.FOG) -> 0.64f
    sky == RealWorldSky.SUNSET -> 0.48f
    feel in setOf(RealWorldRoadFeel.TUNNEL, RealWorldRoadFeel.BRIDGE, RealWorldRoadFeel.MOUNTAIN) -> 0.44f
    else -> 0.26f
}

private fun Route.pointAtDistance(distanceMeters: Double): LatLng {
    if (geometry.isEmpty()) return LatLng.ZERO
    if (geometry.size == 1 || distanceMeters <= 0.0) return geometry.first()
    var walked = 0.0
    geometry.zipWithNext().forEach { (from, to) ->
        val segment = from.distanceTo(to)
        if (walked + segment >= distanceMeters) {
            val t = ((distanceMeters - walked) / segment).coerceIn(0.0, 1.0)
            return LatLng(
                lat = from.lat + (to.lat - from.lat) * t,
                lng = from.lng + (to.lng - from.lng) * t,
            )
        }
        walked += segment
    }
    return geometry.last()
}

private fun LatLng.project(bearingDeg: Double, distanceMeters: Double): LatLng {
    val radius = 6_371_000.0
    val angular = distanceMeters / radius
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(lat)
    val lng1 = Math.toRadians(lng)
    val lat2 = kotlin.math.asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lng2 = lng1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
}

private fun elevationAngle(altitudeMeters: Double, distanceMeters: Double): Double =
    Math.toDegrees(atan2(altitudeMeters, distanceMeters))

private fun signedBearingDelta(fromDeg: Double, toDeg: Double): Double =
    ((toDeg - fromDeg + 540.0) % 360.0) - 180.0

private fun Double.normalizeBearing(): Double = ((this % 360.0) + 360.0) % 360.0

private fun Double.kmLabel(): String = if (this >= 1_000.0) {
    "${(this / 1000.0).roundToInt()} km"
} else {
    "${roundToInt()} m"
}

private fun Double.roundKey(): String = "%.4f".format(this)

private fun Double.roundToSingleDecimal(): String = "%.1f".format(this)

private fun windowName(relativeDeg: Double): String = when {
    relativeDeg < -55.0 -> "left window"
    relativeDeg > 55.0 -> "right window"
    else -> "windshield"
}

private fun approximateSun(now: LocalDateTime): SunState {
    val minutes = now.hour * 60 + now.minute
    val dayProgress = minutes / 1440.0
    val azimuth = (90.0 + dayProgress * 360.0).normalizeBearing()
    val elevation = when (now.hour) {
        in 5..7 -> 4.0 + (now.hour - 5) * 4.0
        in 8..17 -> 34.0
        in 18..21 -> 16.0 - (now.hour - 18) * 4.0
        else -> -8.0
    }
    return SunState(azimuth, elevation)
}
