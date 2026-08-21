package com.navplus.core.navigation

import com.navplus.core.common.model.Lane
import com.navplus.core.common.model.LaneDirection
import com.navplus.core.common.model.LaneGuidance
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.RouteStep
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong

@Singleton
class RoadScenarioSimulator @Inject constructor() {
    private var active: Boolean = false

    val isActive: Boolean
        get() = active

    val scenario: RoadScenario = RoadScenarioCatalog.complexUrbanDrive()

    fun start() {
        active = true
    }

    fun stop() {
        active = false
    }

    fun locationUpdates(intervalMs: Long): Flow<Location> = flow {
        var elapsedSeconds = 0.0
        while (isActive) {
            emit(locationAt(elapsedSeconds))
            elapsedSeconds += intervalMs / 1_000.0
            delay(intervalMs)
        }
    }

    private fun locationAt(elapsedSeconds: Double): Location {
        val distanceMeters = (elapsedSeconds * SIMULATION_SPEED_MPS)
            .coerceAtMost(scenario.route.distanceMeters)
        val fix = scenario.route.pointAtDistance(distanceMeters)
        val speedKph = scenario.speedAtDistance(distanceMeters)
        return Location(
            latLng = fix.point,
            bearingDeg = fix.bearingDeg,
            speedMps = speedKph / 3.6f,
            accuracyMeters = 5f,
            timestampMs = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val SIMULATION_SPEED_MPS = 19.0
    }
}

data class RoadScenario(
    val route: Route,
    val cameras: List<SpeedCamera>,
    private val speedBands: List<SpeedBand>,
) {
    fun speedAtDistance(distanceMeters: Double): Float {
        return speedBands.firstOrNull { distanceMeters in it.startMeters..it.endMeters }?.speedKph ?: 45f
    }
}

data class SpeedBand(
    val startMeters: Double,
    val endMeters: Double,
    val speedKph: Float,
)

private data class RouteFix(
    val point: LatLng,
    val bearingDeg: Float,
)

private object RoadScenarioCatalog {
    fun complexUrbanDrive(): RoadScenario {
        val start = LatLng(48.70480, 9.57800)
        val points = listOf(
            start,
            start.offsetMeters(north = 380.0, east = 0.0),
            start.offsetMeters(north = 760.0, east = 130.0),
            start.offsetMeters(north = 1_040.0, east = 420.0),
            start.offsetMeters(north = 1_100.0, east = 650.0),
            start.offsetMeters(north = 1_450.0, east = 760.0),
            start.offsetMeters(north = 1_800.0, east = 710.0),
            start.offsetMeters(north = 2_120.0, east = 930.0),
            start.offsetMeters(north = 2_420.0, east = 1_150.0),
            start.offsetMeters(north = 2_680.0, east = 1_150.0),
        )

        val steps = listOf(
            step("Continue toward camera", Maneuver.STRAIGHT, points[0], points[1], speedLimit = 50),
            step("Enter residential 30 school zone", Maneuver.STRAIGHT, points[1], points[2], speedLimit = 30),
            step(
                instruction = "At roundabout take exit 2",
                maneuver = Maneuver.ROUNDABOUT_ENTER,
                start = points[2],
                end = points[3],
                speedLimit = 30,
                geometry = listOf(
                    points[2],
                    points[2].offsetMeters(north = 80.0, east = 18.0),
                    points[2].offsetMeters(north = 145.0, east = 95.0),
                    points[2].offsetMeters(north = 205.0, east = 205.0),
                    points[3],
                ),
                laneGuidance = LaneGuidance(
                    lanes = listOf(
                        Lane(listOf(LaneDirection.LEFT), isActive = false),
                        Lane(listOf(LaneDirection.SLIGHT_RIGHT), isActive = true),
                        Lane(listOf(LaneDirection.RIGHT), isActive = false),
                    ),
                    recommendedIndices = listOf(1),
                ),
                exitNumber = "2",
            ),
            step("Traffic calming area", Maneuver.STRAIGHT, points[3], points[4], speedLimit = 30),
            step(
                instruction = "Roundabout exit 2 use lane 2",
                maneuver = Maneuver.ROUNDABOUT_ENTER,
                start = points[4],
                end = points[5],
                speedLimit = 30,
                geometry = listOf(
                    points[4],
                    points[4].offsetMeters(north = 90.0, east = 2.0),
                    points[4].offsetMeters(north = 165.0, east = 52.0),
                    points[4].offsetMeters(north = 255.0, east = 86.0),
                    points[5],
                ),
                laneGuidance = LaneGuidance(
                    lanes = listOf(
                        Lane(listOf(LaneDirection.LEFT), isActive = false),
                        Lane(listOf(LaneDirection.SLIGHT_RIGHT), isActive = true),
                        Lane(listOf(LaneDirection.RIGHT), isActive = false),
                    ),
                    recommendedIndices = listOf(1),
                ),
                exitNumber = "2",
            ),
            step(
                "Residential school zone traffic calming",
                Maneuver.STRAIGHT,
                points[5],
                points[6],
                speedLimit = 30,
                geometry = listOf(
                    points[5],
                    points[5].offsetMeters(north = 140.0, east = -8.0),
                    points[5].offsetMeters(north = 270.0, east = -38.0),
                    points[6],
                ),
            ),
            step(
                instruction = "Priority road busy junction keep right noise protection",
                maneuver = Maneuver.KEEP_RIGHT,
                start = points[6],
                end = points[7],
                speedLimit = 50,
                geometry = listOf(
                    points[6],
                    points[6].offsetMeters(north = 95.0, east = 26.0),
                    points[6].offsetMeters(north = 205.0, east = 116.0),
                    points[7],
                ),
                laneGuidance = LaneGuidance(
                    lanes = listOf(
                        Lane(listOf(LaneDirection.STRAIGHT), isActive = false),
                        Lane(listOf(LaneDirection.STRAIGHT, LaneDirection.SLIGHT_RIGHT), isActive = true),
                        Lane(listOf(LaneDirection.RIGHT, LaneDirection.EXIT), isActive = true),
                    ),
                    recommendedIndices = listOf(1, 2),
                ),
            ),
            step(
                "Give way before tunnel ahead",
                Maneuver.TUNNEL,
                points[7],
                points[8],
                speedLimit = 60,
                geometry = listOf(
                    points[7],
                    points[7].offsetMeters(north = 110.0, east = 80.0),
                    points[7].offsetMeters(north = 220.0, east = 160.0),
                    points[8],
                ),
            ),
            step("Stop sign ferry access road", Maneuver.FERRY, points[8], points[9], speedLimit = 20),
        )
        val routeGeometry = steps.flatMapIndexed { index, step ->
            if (index == 0) step.geometry else step.geometry.drop(1)
        }

        val route = Route(
            id = "debug-road-scenario",
            waypoints = listOf(points.first(), points.last()),
            geometry = routeGeometry,
            steps = steps,
            distanceMeters = steps.sumOf { it.distanceMeters },
            durationSeconds = (steps.sumOf { it.distanceMeters } / 13.0).roundToLong(),
        )
        val cameraPoint = route.pointAtDistance(620.0).point
        val redLightCameraPoint = route.pointAtDistance(1_520.0).point

        return RoadScenario(
            route = route,
            cameras = listOf(
                SpeedCamera(
                    id = "debug-speed-camera-620",
                    lat = cameraPoint.lat,
                    lng = cameraPoint.lng,
                    type = CameraType.FIXED_SPEED,
                    directionDeg = route.pointAtDistance(620.0).bearingDeg,
                    speedLimitKph = 30,
                    country = "DE",
                    source = "debug-scenario",
                ),
                SpeedCamera(
                    id = "debug-red-light-camera-1520",
                    lat = redLightCameraPoint.lat,
                    lng = redLightCameraPoint.lng,
                    type = CameraType.RED_LIGHT,
                    directionDeg = route.pointAtDistance(1_520.0).bearingDeg,
                    speedLimitKph = 50,
                    country = "DE",
                    source = "debug-scenario",
                ),
            ),
            speedBands = listOf(
                SpeedBand(0.0, 520.0, 48f),
                SpeedBand(520.0, 950.0, 54f),
                SpeedBand(950.0, 1_250.0, 28f),
                SpeedBand(1_250.0, 1_700.0, 58f),
                SpeedBand(1_700.0, 2_250.0, 61f),
                SpeedBand(2_250.0, 3_000.0, 24f),
            ),
        )
    }

    private fun step(
        instruction: String,
        maneuver: Maneuver,
        start: LatLng,
        end: LatLng,
        speedLimit: Int,
        geometry: List<LatLng> = listOf(start, end),
        laneGuidance: LaneGuidance? = null,
        exitNumber: String? = null,
    ): RouteStep {
        val distance = start.distanceTo(end)
        return RouteStep(
            instruction = instruction,
            maneuver = maneuver,
            distanceMeters = distance,
            durationSeconds = (distance / 12.0).roundToLong(),
            startLocation = start,
            endLocation = end,
            geometry = geometry,
            laneGuidance = laneGuidance,
            streetName = instruction,
            exitNumber = exitNumber,
            speedLimitKph = speedLimit,
        )
    }
}

private fun Route.pointAtDistance(distanceMeters: Double): RouteFix {
    if (geometry.size < 2) return RouteFix(geometry.firstOrNull() ?: LatLng(0.0, 0.0), 0f)
    var remaining = distanceMeters.coerceAtLeast(0.0)
    for (i in 0 until geometry.lastIndex) {
        val start = geometry[i]
        val end = geometry[i + 1]
        val segmentDistance = start.distanceTo(end)
        if (segmentDistance <= 0.0) continue
        if (remaining <= segmentDistance) {
            val fraction = remaining / segmentDistance
            return RouteFix(
                point = start.interpolate(end, fraction),
                bearingDeg = start.bearingTo(end).toFloat(),
            )
        }
        remaining -= segmentDistance
    }
    val lastSegmentStart = geometry[geometry.lastIndex - 1]
    val last = geometry.last()
    return RouteFix(last, lastSegmentStart.bearingTo(last).toFloat())
}

private fun LatLng.interpolate(end: LatLng, fraction: Double): LatLng = LatLng(
    lat = lat + (end.lat - lat) * fraction.coerceIn(0.0, 1.0),
    lng = lng + (end.lng - lng) * fraction.coerceIn(0.0, 1.0),
)

private fun LatLng.offsetMeters(north: Double, east: Double): LatLng {
    val latMeters = 111_320.0
    val lngMeters = latMeters * cos(Math.toRadians(lat))
    return LatLng(
        lat = lat + north / latMeters,
        lng = lng + east / lngMeters,
    )
}
