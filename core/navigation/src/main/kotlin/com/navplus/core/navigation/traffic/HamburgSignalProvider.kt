package com.navplus.core.navigation.traffic

import com.navplus.core.common.model.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HamburgSignalProvider @Inject constructor(
    private val registry: TrafficSignalSourceRegistry,
) : TrafficSignalProvider {
    override val providerId: String = "hamburg_open"

    override suspend fun getSignalsAround(
        route: Route,
        corridor: RouteSignalCorridor,
    ): List<TrafficSignal> = withContext(Dispatchers.IO) {
        val config = registry.enabledForProvider(providerId).firstOrNull { it.endpoint != null }
            ?: return@withContext emptyList()
        val endpoint = config.endpoint ?: return@withContext emptyList()
        runCatching {
            val url = URL(endpoint.withCorridor(corridor))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            connection.inputStream.bufferedReader().use { parseSensorThingsSignals(it.readText()) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getSignalState(signal: TrafficSignal): TrafficSignal? = signal

    override suspend fun getIntersectionMap(intersectionId: String): IntersectionMap? = null

    override fun getCapabilities(): TrafficSignalProviderCapabilities {
        val enabled = registry.enabledForProvider(providerId).any { it.endpoint != null }
        return TrafficSignalProviderCapabilities(
            providerId = providerId,
            capabilities = setOf(
                TrafficSignalCapability.LIVE_STATE,
                TrafficSignalCapability.TIMING,
                TrafficSignalCapability.MAPEM,
                TrafficSignalCapability.SPATEM,
            ),
            endpointStatus = if (enabled) TrafficSignalEndpointStatus.LIVE_OPEN else TrafficSignalEndpointStatus.CONFIGURED_BUT_UNAVAILABLE,
            enabled = enabled,
            freshnessWindowMs = 15_000L,
            priority = 5,
            reliability = 0.86f,
        )
    }

    internal fun parseSensorThingsSignals(json: String, nowMs: Long = System.currentTimeMillis()): List<TrafficSignal> {
        val root = JSONObject(json)
        val values = root.optJSONArray("value") ?: return emptyList()
        val signals = ArrayList<TrafficSignal>(values.length())
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val id = item.optString("@iot.id").ifBlank { item.optString("id").ifBlank { index.toString() } }
            val properties = item.optJSONObject("properties") ?: item
            val lat = properties.optDouble("latitude", Double.NaN)
                .takeUnless { it.isNaN() }
                ?: item.optLocationCoordinate(latitude = true)
                ?: continue
            val lng = properties.optDouble("longitude", Double.NaN)
                .takeUnless { it.isNaN() }
                ?: item.optLocationCoordinate(latitude = false)
                ?: continue
            val observedAt = properties.optString("phenomenonTime")
                .ifBlank { properties.optString("resultTime") }
                .parseIsoEpochMs()
                ?: nowMs
            val state = properties.optString("state")
                .ifBlank { properties.optString("result") }
                .toSignalState()
            signals.add(
                TrafficSignal(
                    id = "hamburg_$id",
                    intersectionId = properties.optString("intersectionId").ifBlank { properties.optString("thingId").ifBlank { null } },
                    latitude = lat,
                    longitude = lng,
                    roadEdgeId = properties.optString("roadEdgeId").ifBlank { null },
                    laneIds = properties.optString("laneId").ifBlank { null }?.let(::listOf).orEmpty(),
                    movement = properties.optString("movement").toSignalMovementOrNull(),
                    bearing = properties.optDouble("bearing", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
                    distanceAlongRoute = null,
                    state = state,
                    stateSourceType = if (state == SignalState.UNKNOWN) SignalSourceType.STATIC else SignalSourceType.LIVE,
                    phaseStartEpochMs = properties.optString("phaseStart").parseIsoEpochMs(),
                    phaseEndEpochMs = properties.optString("phaseEnd").parseIsoEpochMs(),
                    predictedChangeEpochMs = properties.optString("predictedChangeTime").parseIsoEpochMs(),
                    confidence = properties.optDouble("confidence", 0.82).toFloat().coerceIn(0f, 1f),
                    lastUpdatedEpochMs = observedAt,
                    providerId = providerId,
                    providerSignalId = id,
                    supportsLiveState = state != SignalState.UNKNOWN,
                    supportsTiming = properties.has("phaseEnd") || properties.has("predictedChangeTime"),
                    supportsGlosa = properties.has("phaseEnd") || properties.has("predictedChangeTime"),
                    metadata = mapOf("source" to "Hamburg Traffic Lights Data"),
                )
            )
        }
        return signals
    }

    private fun String.withCorridor(corridor: RouteSignalCorridor): String {
        val separator = if (contains("?")) "&" else "?"
        return this + separator +
            "minLat=${corridor.minLat}&maxLat=${corridor.maxLat}&minLng=${corridor.minLng}&maxLng=${corridor.maxLng}"
    }

    private fun JSONObject.optLocationCoordinate(latitude: Boolean): Double? {
        val locations = optJSONArray("Locations") ?: optJSONArray("locations") ?: return null
        val location = locations.optJSONObject(0) ?: return null
        val coordinates = location.optJSONObject("location")?.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        return coordinates.optDouble(if (latitude) 1 else 0, Double.NaN).takeUnless { it.isNaN() }
    }

    private fun String.parseIsoEpochMs(): Long? =
        runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun String.toSignalState(): SignalState = when (trim().lowercase()) {
        "red", "rot", "r" -> SignalState.RED
        "red_yellow", "red-yellow", "rotgelb", "rot-gelb" -> SignalState.RED_YELLOW
        "yellow", "amber", "gelb", "y" -> SignalState.YELLOW
        "green", "gruen", "grün", "g" -> SignalState.GREEN
        "flashing", "blinkend" -> SignalState.FLASHING
        "off", "aus" -> SignalState.OFF
        else -> SignalState.UNKNOWN
    }

    private fun String.toSignalMovementOrNull(): SignalMovement? = when (trim().lowercase()) {
        "straight", "geradeaus" -> SignalMovement.STRAIGHT
        "left", "links" -> SignalMovement.LEFT
        "right", "rechts" -> SignalMovement.RIGHT
        "u_turn", "uturn", "wenden" -> SignalMovement.U_TURN
        "bus" -> SignalMovement.BUS
        "tram", "strassenbahn", "straßenbahn" -> SignalMovement.TRAM
        "cycle", "bike", "rad" -> SignalMovement.CYCLE
        "pedestrian", "fussgaenger", "fußgänger" -> SignalMovement.PEDESTRIAN
        else -> null
    }
}
