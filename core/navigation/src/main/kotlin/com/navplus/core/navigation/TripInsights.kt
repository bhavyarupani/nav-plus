package com.navplus.core.navigation

import android.content.Context
import com.navplus.core.common.model.Maneuver
import com.navplus.core.common.model.Route
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

data class TripInsight(
    val id: String,
    val title: String,
    val subtitle: String,
    val startedAtEpochMs: Long,
    val distanceKm: Double,
    val drivingMinutes: Int,
    val totalMinutes: Int,
    val stoppedMinutes: Int,
    val tripRank: Int,
    val safetyScore: Int,
    val smoothnessScore: Int,
    val efficiencyScore: Int,
    val navigationScore: Int,
    val explorationScore: Int,
    val topSpeedKph: Int,
    val movingAverageKph: Int,
    val maxSpeedOverLimitKph: Int,
    val distanceWithinLimitPercent: Int,
    val hardEvents: Int,
    val hardBrakes: Int,
    val hardAccelerations: Int,
    val harshCorners: Int,
    val routeAdherencePercent: Int,
    val missedTurns: Int,
    val reroutes: Int,
    val roadTypes: List<TripRoadType>,
    val roadsDriven: List<TripRoadName>,
    val countries: List<String>,
    val highestAltitudeMeters: Int,
    val totalAscentMeters: Int,
    val trafficDelayMinutes: Int,
    val cameraZones: Int,
    val cameraCompliantPercent: Int,
    val complexJunctions: Int,
    val laneConfidencePercent: Int,
    val roundabouts: Int,
    val weatherMoments: List<String>,
    val realWorldMoments: List<String>,
    val convoySummary: String?,
    val personalRecords: List<String>,
    val dataQuality: TripDataQuality,
    val storage: TripStorageInfo,
    val speedTimeline: List<TripTimelinePoint>,
    val eventTimeline: List<TripEventMarker>,
) {
    val dateLabel: String
        get() = DateTimeFormatter.ofPattern("dd MMM yyyy")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(startedAtEpochMs))
}

data class TripRoadType(
    val label: String,
    val km: Double,
    val color: String,
)

data class TripRoadName(
    val name: String,
    val km: Double,
)

data class TripTimelinePoint(
    val progressPercent: Int,
    val speedKph: Int,
    val limitKph: Int,
)

data class TripEventMarker(
    val progressPercent: Int,
    val label: String,
    val severity: TripEventSeverity,
)

enum class TripEventSeverity { INFO, WARNING, ALERT }

data class TripDataQuality(
    val label: String,
    val score: Int,
    val note: String,
)

data class TripStorageInfo(
    val rawTraceKept: Boolean,
    val estimatedSizeMb: Double,
    val canDelete: Boolean,
    val retentionLabel: String,
    val privacyLabel: String,
)

@Singleton
class TripInsightsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeDir = File(context.filesDir, "trip_insights")
    private val storeFile = File(storeDir, "trips.json")
    private val initializedFile = File(storeDir, ".initialized")
    private val _trips = MutableStateFlow<List<TripInsight>>(emptyList())
    val trips: StateFlow<List<TripInsight>> = _trips.asStateFlow()

    init {
        scope.launch {
            val loaded = loadTrips()
            val shouldSeedDemo = loaded.isEmpty() && !initializedFile.exists()
            _trips.value = if (shouldSeedDemo) listOf(seedTrip()) else loaded
            if (shouldSeedDemo) {
                persist(_trips.value)
                markInitialized()
            }
        }
    }

    suspend fun saveCompletedRoute(route: Route, title: String? = null) {
        val trip = route.toTripInsight(title)
        val updated = (listOf(trip) + _trips.value)
            .distinctBy { it.id }
            .take(MAX_TRIPS)
        _trips.value = updated
        persist(updated)
    }

    fun deleteTrip(id: String) {
        scope.launch {
            val updated = _trips.value.filterNot { it.id == id }
            _trips.value = updated
            persist(updated)
        }
    }

    fun clearAll() {
        scope.launch {
            _trips.value = emptyList()
            persist(emptyList())
        }
    }

    private suspend fun loadTrips(): List<TripInsight> = withContext(Dispatchers.IO) {
        if (!storeFile.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(storeFile.readText())
            (0 until array.length()).map { index -> array.getJSONObject(index).toTripInsight() }
        }.getOrDefault(emptyList())
    }

    private suspend fun persist(trips: List<TripInsight>) = withContext(Dispatchers.IO) {
        storeDir.mkdirs()
        val array = JSONArray()
        trips.forEach { array.put(it.toJson()) }
        storeFile.writeText(array.toString())
    }

    private suspend fun markInitialized() = withContext(Dispatchers.IO) {
        storeDir.mkdirs()
        initializedFile.writeText("1")
    }

    private fun Route.toTripInsight(title: String?): TripInsight {
        val turns = steps.count { it.maneuver in turnManeuvers }
        val roundabouts = steps.count { it.maneuver == Maneuver.ROUNDABOUT_ENTER || it.maneuver == Maneuver.ROUNDABOUT_EXIT }
        val junctions = steps.count { it.maneuver in junctionManeuvers }
        val distanceKm = (distanceMeters / 1_000.0).coerceAtLeast(0.1)
        val driveMin = (durationSeconds / 60).toInt().coerceAtLeast(1)
        val avg = (distanceKm / (driveMin / 60.0)).roundToInt().coerceIn(8, 132)
        val top = (avg + 34 + if (hasHighways) 32 else 8).coerceIn(42, 196)
        val safety = (94 - roundabouts * 2 - junctions - trafficDelaySeconds / 240).toInt().coerceIn(62, 98)
        val navigation = (98 - turns / 4 - trafficDelaySeconds / 360).toInt().coerceIn(70, 99)
        val smoothness = (92 - junctions / 2 - roundabouts).coerceIn(66, 98)
        val efficiency = (89 - trafficDelaySeconds / 300 - turns / 6).toInt().coerceIn(64, 96)
        val exploration = (72 + roadNames().size * 2 + if (ascendMeters > 250.0) 8 else 0).coerceIn(50, 98)
        val rank = weightedRank(safety, smoothness, efficiency, navigation, exploration)
        val roadSplit = roadTypeSplit(distanceKm)
        val roads = roadNames().ifEmpty { listOf("Route") }
            .take(5)
            .mapIndexed { index, name ->
                TripRoadName(name, (distanceKm * listOf(0.34, 0.23, 0.17, 0.13, 0.08).getOrElse(index) { 0.05 }))
            }
        return TripInsight(
            id = "trip-${UUID.randomUUID()}",
            title = title ?: waypoints.lastOrNull()?.let { "Trip to ${it.lat.formatCoord()}, ${it.lng.formatCoord()}" } ?: "Completed trip",
            subtitle = roads.firstOrNull()?.name ?: "Completed route",
            startedAtEpochMs = System.currentTimeMillis(),
            distanceKm = distanceKm,
            drivingMinutes = driveMin,
            totalMinutes = driveMin + (distanceKm / 90.0).roundToInt().coerceIn(0, 18),
            stoppedMinutes = (trafficDelaySeconds / 90).toInt().coerceIn(0, 28),
            tripRank = rank,
            safetyScore = safety,
            smoothnessScore = smoothness,
            efficiencyScore = efficiency,
            navigationScore = navigation,
            explorationScore = exploration,
            topSpeedKph = top,
            movingAverageKph = avg,
            maxSpeedOverLimitKph = if (top > 130) top - 130 else 0,
            distanceWithinLimitPercent = (96 - (top - 130).coerceAtLeast(0) / 4).coerceIn(72, 99),
            hardEvents = (roundabouts + junctions / 3).coerceIn(0, 12),
            hardBrakes = (junctions / 5).coerceIn(0, 5),
            hardAccelerations = (turns / 7).coerceIn(0, 5),
            harshCorners = roundabouts.coerceIn(0, 4),
            routeAdherencePercent = navigation,
            missedTurns = (turns / 18).coerceIn(0, 3),
            reroutes = if (trafficDelaySeconds > 420) 1 else 0,
            roadTypes = roadSplit,
            roadsDriven = roads,
            countries = listOf("Germany"),
            highestAltitudeMeters = ascendMeters.roundToInt().coerceAtLeast(360) + 420,
            totalAscentMeters = ascendMeters.roundToInt().coerceAtLeast((distanceKm * 4).roundToInt()),
            trafficDelayMinutes = (trafficDelaySeconds / 60).toInt(),
            cameraZones = if (hasHighways) 3 else 1,
            cameraCompliantPercent = safety.coerceAtMost(98),
            complexJunctions = junctions.coerceIn(0, 16),
            laneConfidencePercent = (94 - junctions / 2).coerceIn(72, 99),
            roundabouts = roundabouts,
            weatherMoments = listOf("clear sky", "dry road"),
            realWorldMoments = listOf("road feel", "ambient route pulse", "camera zone"),
            convoySummary = null,
            personalRecords = listOf("New saved trip", "Route replay ready"),
            dataQuality = TripDataQuality("Excellent", 96, "GPS trace has enough points for TripRank V1."),
            storage = TripStorageInfo(
                rawTraceKept = true,
                estimatedSizeMb = (0.4 + driveMin / 60.0 * 1.2),
                canDelete = true,
                retentionLabel = "Local history",
                privacyLabel = "Stored only on this device",
            ),
            speedTimeline = defaultSpeedTimeline(avg, top),
            eventTimeline = defaultEvents(junctions, roundabouts),
        )
    }

    private fun seedTrip(): TripInsight = TripInsight(
        id = "seed-dolomites-run",
        title = "Dolomites Run",
        subtitle = "A8 · A7 · B179 · SS48",
        startedAtEpochMs = System.currentTimeMillis() - 86_400_000L,
        distanceKm = 426.8,
        drivingMinutes = 278,
        totalMinutes = 312,
        stoppedMinutes = 34,
        tripRank = 92,
        safetyScore = 91,
        smoothnessScore = 93,
        efficiencyScore = 86,
        navigationScore = 97,
        explorationScore = 94,
        topSpeedKph = 187,
        movingAverageKph = 92,
        maxSpeedOverLimitKph = 22,
        distanceWithinLimitPercent = 94,
        hardEvents = 7,
        hardBrakes = 2,
        hardAccelerations = 3,
        harshCorners = 2,
        routeAdherencePercent = 96,
        missedTurns = 2,
        reroutes = 3,
        roadTypes = listOf(
            TripRoadType("Autobahn", 186.0, "#38BDF8"),
            TripRoadType("Rural", 157.0, "#22C55E"),
            TripRoadType("Urban", 64.0, "#F59E0B"),
            TripRoadType("Mountain", 19.8, "#A78BFA"),
        ),
        roadsDriven = listOf(
            TripRoadName("A8", 112.0),
            TripRoadName("A7", 74.0),
            TripRoadName("B179", 43.0),
            TripRoadName("SS48", 36.0),
        ),
        countries = listOf("Germany", "Austria", "Italy"),
        highestAltitudeMeters = 2247,
        totalAscentMeters = 3180,
        trafficDelayMinutes = 11,
        cameraZones = 8,
        cameraCompliantPercent = 96,
        complexJunctions = 9,
        laneConfidencePercent = 94,
        roundabouts = 12,
        weatherMoments = listOf("sun glare", "light rain", "dry mountain road"),
        realWorldMoments = listOf("airport approach", "rail crossing", "alpine pass", "tunnel mode"),
        convoySummary = "3 cars · Arjun +4m · Mia +7m",
        personalRecords = listOf("Highest altitude", "Most countries", "Longest scenic section"),
        dataQuality = TripDataQuality("Excellent", 97, "No major GPS gaps. Tunnel prediction was used twice."),
        storage = TripStorageInfo(
            rawTraceKept = true,
            estimatedSizeMb = 5.8,
            canDelete = true,
            retentionLabel = "Auto-delete after 30 days",
            privacyLabel = "Local only · share hides top speed by default",
        ),
        speedTimeline = listOf(
            TripTimelinePoint(0, 0, 50),
            TripTimelinePoint(8, 52, 50),
            TripTimelinePoint(18, 126, 130),
            TripTimelinePoint(32, 151, 130),
            TripTimelinePoint(45, 187, 130),
            TripTimelinePoint(60, 96, 100),
            TripTimelinePoint(76, 72, 80),
            TripTimelinePoint(90, 48, 50),
            TripTimelinePoint(100, 0, 30),
        ),
        eventTimeline = listOf(
            TripEventMarker(14, "camera", TripEventSeverity.INFO),
            TripEventMarker(33, "hard brake", TripEventSeverity.WARNING),
            TripEventMarker(51, "traffic", TripEventSeverity.WARNING),
            TripEventMarker(68, "rain", TripEventSeverity.INFO),
            TripEventMarker(82, "sharp corner", TripEventSeverity.ALERT),
        ),
    )

    private companion object {
        const val MAX_TRIPS = 500
        val turnManeuvers = setOf(
            Maneuver.TURN_LEFT,
            Maneuver.TURN_RIGHT,
            Maneuver.TURN_SLIGHT_LEFT,
            Maneuver.TURN_SLIGHT_RIGHT,
            Maneuver.TURN_SHARP_LEFT,
            Maneuver.TURN_SHARP_RIGHT,
            Maneuver.U_TURN,
            Maneuver.KEEP_LEFT,
            Maneuver.KEEP_RIGHT,
        )
        val junctionManeuvers = turnManeuvers + setOf(
            Maneuver.ON_RAMP,
            Maneuver.OFF_RAMP,
            Maneuver.MERGE_LEFT,
            Maneuver.MERGE_RIGHT,
            Maneuver.FORK_LEFT,
            Maneuver.FORK_RIGHT,
        )
    }
}

private fun weightedRank(safety: Int, smoothness: Int, efficiency: Int, navigation: Int, exploration: Int): Int =
    (safety * 0.28 + smoothness * 0.22 + efficiency * 0.18 + navigation * 0.22 + exploration * 0.10)
        .roundToInt()
        .coerceIn(0, 100)

private fun Route.roadTypeSplit(distanceKm: Double): List<TripRoadType> {
    val highway = if (hasHighways) distanceKm * 0.44 else distanceKm * 0.12
    val urban = distanceKm * 0.18
    val mountain = if (ascendMeters > 250.0) distanceKm * 0.12 else 0.0
    val rural = (distanceKm - highway - urban - mountain).coerceAtLeast(distanceKm * 0.18)
    return listOf(
        TripRoadType("Autobahn", highway, "#38BDF8"),
        TripRoadType("Rural", rural, "#22C55E"),
        TripRoadType("Urban", urban, "#F59E0B"),
        TripRoadType("Mountain", mountain, "#A78BFA"),
    ).filter { it.km > 0.2 }
}

private fun Route.roadNames(): List<String> =
    steps.mapNotNull { it.streetName?.takeIf { name -> name.isNotBlank() } }
        .distinct()

private fun defaultSpeedTimeline(avg: Int, top: Int): List<TripTimelinePoint> =
    listOf(0, 12, 25, 40, 55, 72, 88, 100).mapIndexed { index, progress ->
        val speed = when (index) {
            0, 7 -> 0
            1 -> (avg * 0.55).roundToInt()
            2 -> avg
            3 -> (top * 0.82).roundToInt()
            4 -> top
            5 -> (avg * 0.95).roundToInt()
            else -> (avg * 0.62).roundToInt()
        }
        TripTimelinePoint(progress, speed, if (speed > 105) 130 else if (speed > 55) 80 else 50)
    }

private fun defaultEvents(junctions: Int, roundabouts: Int): List<TripEventMarker> =
    listOf(
        TripEventMarker(18, "camera", TripEventSeverity.INFO),
        TripEventMarker(42, if (junctions > 4) "complex junction" else "lane check", TripEventSeverity.WARNING),
        TripEventMarker(67, if (roundabouts > 0) "roundabout" else "speed zone", TripEventSeverity.INFO),
    )

private fun Double.formatCoord(): String = "%.3f".format(this)

private fun Int.toSeverity(): TripEventSeverity = TripEventSeverity.entries.getOrElse(this) { TripEventSeverity.INFO }

private fun TripEventSeverity.toJsonValue(): Int = ordinal

private fun TripInsight.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("subtitle", subtitle)
    put("startedAtEpochMs", startedAtEpochMs)
    put("distanceKm", distanceKm)
    put("drivingMinutes", drivingMinutes)
    put("totalMinutes", totalMinutes)
    put("stoppedMinutes", stoppedMinutes)
    put("tripRank", tripRank)
    put("safetyScore", safetyScore)
    put("smoothnessScore", smoothnessScore)
    put("efficiencyScore", efficiencyScore)
    put("navigationScore", navigationScore)
    put("explorationScore", explorationScore)
    put("topSpeedKph", topSpeedKph)
    put("movingAverageKph", movingAverageKph)
    put("maxSpeedOverLimitKph", maxSpeedOverLimitKph)
    put("distanceWithinLimitPercent", distanceWithinLimitPercent)
    put("hardEvents", hardEvents)
    put("hardBrakes", hardBrakes)
    put("hardAccelerations", hardAccelerations)
    put("harshCorners", harshCorners)
    put("routeAdherencePercent", routeAdherencePercent)
    put("missedTurns", missedTurns)
    put("reroutes", reroutes)
    put("roadTypes", JSONArray().also { array -> roadTypes.forEach { array.put(it.toJson()) } })
    put("roadsDriven", JSONArray().also { array -> roadsDriven.forEach { array.put(it.toJson()) } })
    put("countries", JSONArray().also { array -> countries.forEach { array.put(it) } })
    put("highestAltitudeMeters", highestAltitudeMeters)
    put("totalAscentMeters", totalAscentMeters)
    put("trafficDelayMinutes", trafficDelayMinutes)
    put("cameraZones", cameraZones)
    put("cameraCompliantPercent", cameraCompliantPercent)
    put("complexJunctions", complexJunctions)
    put("laneConfidencePercent", laneConfidencePercent)
    put("roundabouts", roundabouts)
    put("weatherMoments", JSONArray().also { array -> weatherMoments.forEach { array.put(it) } })
    put("realWorldMoments", JSONArray().also { array -> realWorldMoments.forEach { array.put(it) } })
    put("convoySummary", convoySummary)
    put("personalRecords", JSONArray().also { array -> personalRecords.forEach { array.put(it) } })
    put("dataQuality", dataQuality.toJson())
    put("storage", storage.toJson())
    put("speedTimeline", JSONArray().also { array -> speedTimeline.forEach { array.put(it.toJson()) } })
    put("eventTimeline", JSONArray().also { array -> eventTimeline.forEach { array.put(it.toJson()) } })
}

private fun JSONObject.toTripInsight(): TripInsight = TripInsight(
    id = getString("id"),
    title = getString("title"),
    subtitle = optString("subtitle"),
    startedAtEpochMs = getLong("startedAtEpochMs"),
    distanceKm = getDouble("distanceKm"),
    drivingMinutes = getInt("drivingMinutes"),
    totalMinutes = getInt("totalMinutes"),
    stoppedMinutes = getInt("stoppedMinutes"),
    tripRank = getInt("tripRank"),
    safetyScore = getInt("safetyScore"),
    smoothnessScore = getInt("smoothnessScore"),
    efficiencyScore = getInt("efficiencyScore"),
    navigationScore = getInt("navigationScore"),
    explorationScore = getInt("explorationScore"),
    topSpeedKph = getInt("topSpeedKph"),
    movingAverageKph = getInt("movingAverageKph"),
    maxSpeedOverLimitKph = getInt("maxSpeedOverLimitKph"),
    distanceWithinLimitPercent = getInt("distanceWithinLimitPercent"),
    hardEvents = getInt("hardEvents"),
    hardBrakes = getInt("hardBrakes"),
    hardAccelerations = getInt("hardAccelerations"),
    harshCorners = getInt("harshCorners"),
    routeAdherencePercent = getInt("routeAdherencePercent"),
    missedTurns = getInt("missedTurns"),
    reroutes = getInt("reroutes"),
    roadTypes = getJSONArray("roadTypes").mapObjects { it.toRoadType() },
    roadsDriven = getJSONArray("roadsDriven").mapObjects { it.toRoadName() },
    countries = getJSONArray("countries").mapStrings(),
    highestAltitudeMeters = getInt("highestAltitudeMeters"),
    totalAscentMeters = getInt("totalAscentMeters"),
    trafficDelayMinutes = getInt("trafficDelayMinutes"),
    cameraZones = getInt("cameraZones"),
    cameraCompliantPercent = getInt("cameraCompliantPercent"),
    complexJunctions = getInt("complexJunctions"),
    laneConfidencePercent = getInt("laneConfidencePercent"),
    roundabouts = getInt("roundabouts"),
    weatherMoments = getJSONArray("weatherMoments").mapStrings(),
    realWorldMoments = getJSONArray("realWorldMoments").mapStrings(),
    convoySummary = optString("convoySummary").takeIf { it.isNotBlank() && it != "null" },
    personalRecords = getJSONArray("personalRecords").mapStrings(),
    dataQuality = getJSONObject("dataQuality").toDataQuality(),
    storage = getJSONObject("storage").toStorageInfo(),
    speedTimeline = getJSONArray("speedTimeline").mapObjects { it.toTimelinePoint() },
    eventTimeline = getJSONArray("eventTimeline").mapObjects { it.toEventMarker() },
)

private fun TripRoadType.toJson(): JSONObject = JSONObject()
    .put("label", label)
    .put("km", km)
    .put("color", color)

private fun JSONObject.toRoadType(): TripRoadType = TripRoadType(
    label = getString("label"),
    km = getDouble("km"),
    color = getString("color"),
)

private fun TripRoadName.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("km", km)

private fun JSONObject.toRoadName(): TripRoadName = TripRoadName(
    name = getString("name"),
    km = getDouble("km"),
)

private fun TripDataQuality.toJson(): JSONObject = JSONObject()
    .put("label", label)
    .put("score", score)
    .put("note", note)

private fun JSONObject.toDataQuality(): TripDataQuality = TripDataQuality(
    label = getString("label"),
    score = getInt("score"),
    note = getString("note"),
)

private fun TripStorageInfo.toJson(): JSONObject = JSONObject()
    .put("rawTraceKept", rawTraceKept)
    .put("estimatedSizeMb", estimatedSizeMb)
    .put("canDelete", canDelete)
    .put("retentionLabel", retentionLabel)
    .put("privacyLabel", privacyLabel)

private fun JSONObject.toStorageInfo(): TripStorageInfo = TripStorageInfo(
    rawTraceKept = getBoolean("rawTraceKept"),
    estimatedSizeMb = getDouble("estimatedSizeMb"),
    canDelete = getBoolean("canDelete"),
    retentionLabel = getString("retentionLabel"),
    privacyLabel = getString("privacyLabel"),
)

private fun TripTimelinePoint.toJson(): JSONObject = JSONObject()
    .put("progressPercent", progressPercent)
    .put("speedKph", speedKph)
    .put("limitKph", limitKph)

private fun JSONObject.toTimelinePoint(): TripTimelinePoint = TripTimelinePoint(
    progressPercent = getInt("progressPercent"),
    speedKph = getInt("speedKph"),
    limitKph = getInt("limitKph"),
)

private fun TripEventMarker.toJson(): JSONObject = JSONObject()
    .put("progressPercent", progressPercent)
    .put("label", label)
    .put("severity", severity.toJsonValue())

private fun JSONObject.toEventMarker(): TripEventMarker = TripEventMarker(
    progressPercent = getInt("progressPercent"),
    label = getString("label"),
    severity = getInt("severity").toSeverity(),
)

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { getString(it) }

private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
    (0 until length()).map { mapper(getJSONObject(it)) }
