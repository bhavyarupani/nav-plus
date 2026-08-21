package com.navplus.core.safety

import android.util.Log
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.distanceTo
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import com.navplus.core.safety.model.SpeedCameraFetchTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

private const val MAX_TILE_LAT_DEG = 0.75
private const val MAX_TILE_LNG_DEG = 1.00
private const val MAX_TILES_PER_VIEWPORT = 80
private const val MAX_ROUTE_TILES_PER_FETCH = 120
private const val ROUTE_PREFETCH_SPACING_METERS = 12_000.0
private const val ROUTE_CAMERA_PADDING_METERS = 4_000.0
private const val TILE_SUCCESS_TTL_MS = 7L * 24L * 60L * 60L * 1_000L
private const val TILE_FAILURE_BASE_RETRY_MS = 15L * 60L * 1_000L
private const val TILE_FAILURE_MAX_RETRY_MS = 6L * 60L * 60L * 1_000L

@Singleton
class OverpassCameraFetcher @Inject constructor(
    private val cameraDao: SpeedCameraDao,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // Overpass hosts in fallback order. The IP-literal is lambert.openstreetmap.de
    // (65.109.112.52) accessed over HTTP so DNS/TLS issues don't block it.
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "http://65.109.112.52/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "http://overpass-api.de/api/interpreter",
    )
    private val inFlightTiles = linkedSetOf<String>()
    private val maxAttemptsPerEndpoint = 1

    suspend fun fetchAndCache(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double,
    ) = withContext(Dispatchers.IO) {
        val bounds = Bounds.from(minLat, maxLat, minLng, maxLng)
        for (tile in bounds.tiles()) {
            coroutineContext.ensureActive()
            fetchTileIfNeeded(tile)
        }
    }

    suspend fun fetchRouteCorridor(routeGeometry: List<LatLng>) = withContext(Dispatchers.IO) {
        val tiles = routeGeometry.routePrefetchBounds()
            .flatMap { it.tiles() }
            .distinctBy { it.cacheKey() }
            .take(MAX_ROUTE_TILES_PER_FETCH)
        for (tile in tiles) {
            coroutineContext.ensureActive()
            fetchTileIfNeeded(tile)
        }
    }

    private suspend fun fetchTileIfNeeded(bounds: Bounds) {
        val key = bounds.cacheKey()
        if (!tryMarkInFlight(key)) return
        try {
            val now = System.currentTimeMillis()
            val cached = cameraDao.getFetchTile(key)
            if (cached != null && !cached.shouldFetch(now)) return
            val fetched = fetchTile(bounds)
            cameraDao.upsertFetchTile(cached.nextState(key, now, fetched))
        } finally {
            clearInFlight(key)
        }
    }

    private suspend fun fetchTile(bounds: Bounds): Boolean {
        val queries = listOf(
            "full" to cameraQuery(bounds),
            "direct" to directCameraQuery(bounds),
        )
        for ((queryName, query) in queries) {
            val body = FormBody.Builder().add("data", query).build()
            for (ep in endpoints) {
                repeat(maxAttemptsPerEndpoint) { attempt ->
                    try {
                        Log.d(TAG, "Overpass $queryName $ep attempt ${attempt + 1} ${bounds.logString()}")
                        val rb = Request.Builder()
                            .url(ep)
                            .post(body)
                            .header("Accept", "*/*")
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", "NavPlus/1.0 (Android; +https://navplus.app)")
                        if (ep.contains("65.109.112.52") || ep.contains("162.55.144.139")) {
                            rb.header("Host", "overpass-api.de")
                        }
                        val request = rb.build()
                        val response = client.newCall(request).execute()
                        val responseBody = response.use { resp ->
                            Log.d(TAG, "Overpass HTTP ${resp.code} ($ep)")
                            if (!resp.isSuccessful) {
                                Log.w(TAG, "Non-2xx from $ep: ${resp.body?.string()?.take(80)}")
                                return@use null
                            }
                            resp.body?.string()
                        } ?: return@repeat
                        val cameras = parseCameras(responseBody)
                        Log.d(TAG, "Overpass $queryName returned ${cameras.size} cameras")
                        if (cameras.isNotEmpty()) cameraDao.upsertAll(cameras)
                        return true
                    } catch (e: Exception) {
                        Log.w(TAG, "Overpass $queryName $ep attempt ${attempt + 1} failed: ${e.message}")
                    }
                }
            }
        }
        Log.e(TAG, "Overpass: all endpoints failed for ${bounds.logString()}")
        return false
    }

    private fun cameraQuery(bounds: Bounds): String {
        val bbox = "${bounds.minLat},${bounds.minLng},${bounds.maxLat},${bounds.maxLng}"
        return """
            [out:json][timeout:25];
            (
              node["highway"="speed_camera"]($bbox);
              node["man_made"="speed_camera"]($bbox);
              relation["type"="enforcement"]["enforcement"~"maxspeed|average_speed|traffic_signals"]($bbox);
            );
            out body center;
            >;
            out body qt;
        """.trimIndent()
    }

    private fun directCameraQuery(bounds: Bounds): String {
        val bbox = "${bounds.minLat},${bounds.minLng},${bounds.maxLat},${bounds.maxLng}"
        return """
            [out:json][timeout:14];
            (
              node["highway"="speed_camera"]($bbox);
              node["man_made"="speed_camera"]($bbox);
            );
            out body;
        """.trimIndent()
    }

    private fun tryMarkInFlight(tileKey: String): Boolean =
        synchronized(inFlightTiles) {
            if (tileKey in inFlightTiles) {
                false
            } else {
                inFlightTiles.add(tileKey)
                true
            }
        }

    private fun clearInFlight(tileKey: String) {
        synchronized(inFlightTiles) {
            inFlightTiles.remove(tileKey)
        }
    }

    companion object {
        private const val TAG = "OverpassFetcher"
    }
}

private fun SpeedCameraFetchTile?.shouldFetch(nowMs: Long): Boolean {
    if (this == null) return true
    val successAgeMs = lastSuccessMs?.let { nowMs - it }
    if (successAgeMs != null && successAgeMs in 0 until TILE_SUCCESS_TTL_MS) return false

    val failureAgeMs = lastFailureMs?.let { nowMs - it } ?: return true
    val retryMs = (TILE_FAILURE_BASE_RETRY_MS * failureCount.coerceAtLeast(1))
        .coerceAtMost(TILE_FAILURE_MAX_RETRY_MS)
    return failureAgeMs >= retryMs
}

private fun SpeedCameraFetchTile?.nextState(tileKey: String, nowMs: Long, success: Boolean): SpeedCameraFetchTile =
    if (success) {
        SpeedCameraFetchTile(
            tileKey = tileKey,
            lastSuccessMs = nowMs,
            lastFailureMs = this?.lastFailureMs,
            failureCount = 0,
        )
    } else {
        SpeedCameraFetchTile(
            tileKey = tileKey,
            lastSuccessMs = this?.lastSuccessMs,
            lastFailureMs = nowMs,
            failureCount = (this?.failureCount ?: 0) + 1,
        )
    }

private fun List<LatLng>.routePrefetchBounds(): List<Bounds> {
    if (isEmpty()) return emptyList()
    if (size == 1) return listOf(first().boundsAround(ROUTE_CAMERA_PADDING_METERS))

    val samples = mutableListOf(first())
    var distanceSinceSample = 0.0
    for (index in 1 until size) {
        val previous = this[index - 1]
        val current = this[index]
        val segmentMeters = previous.distanceTo(current)
        distanceSinceSample += segmentMeters
        if (distanceSinceSample >= ROUTE_PREFETCH_SPACING_METERS) {
            samples.add(current)
            distanceSinceSample = 0.0
        }
    }
    if (samples.last() != last()) samples.add(last())

    return samples.map { it.boundsAround(ROUTE_CAMERA_PADDING_METERS) }
}

private fun LatLng.boundsAround(radiusMeters: Double): Bounds {
    val latPadding = radiusMeters / 111_000.0
    val lngPadding = radiusMeters / (111_000.0 * abs(cos(Math.toRadians(lat))).coerceAtLeast(0.1))
    return Bounds.from(
        minLat = lat - latPadding,
        maxLat = lat + latPadding,
        minLng = lng - lngPadding,
        maxLng = lng + lngPadding,
    )
}

internal fun parseCameras(json: String): List<SpeedCamera> {
    val elements = JSONObject(json).getJSONArray("elements")
    val nodes = linkedMapOf<Long, OsmNode>()
    val ways = linkedMapOf<Long, OsmWay>()
    val relations = mutableListOf<JSONObject>()

    for (i in 0 until elements.length()) {
        val element = elements.getJSONObject(i)
        when (element.optString("type")) {
            "node" -> {
                val id = element.optLong("id")
                val lat = element.optDouble("lat", Double.NaN)
                val lng = element.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    nodes[id] = OsmNode(
                        lat = lat,
                        lng = lng,
                        tags = element.optJSONObject("tags"),
                    )
                }
            }
            "way" -> {
                ways[element.optLong("id")] = OsmWay(
                    nodeIds = element.optJSONArray("nodes").longs(),
                    center = element.optJSONObject("center")?.toPoint(),
                    tags = element.optJSONObject("tags"),
                )
            }
            "relation" -> relations.add(element)
        }
    }

    val byId = linkedMapOf<String, SpeedCamera>()
    val byCoordinate = linkedMapOf<String, String>()

    fun add(camera: SpeedCamera) {
        val coordKey = "${(camera.lat * 100_000).roundToInt()},${(camera.lng * 100_000).roundToInt()}"
        val existingId = byCoordinate[coordKey]
        if (existingId == null) {
            byCoordinate[coordKey] = camera.id
            byId[camera.id] = camera
            return
        }
        val existing = byId[existingId] ?: return
        if (camera.rank() >= existing.rank()) {
            byId.remove(existingId)
            byCoordinate[coordKey] = camera.id
            byId[camera.id] = camera
        }
    }

    nodes.forEach { (id, node) ->
        val tags = node.tags ?: return@forEach
        val highway = tags.optString("highway")
        val manMade = tags.optString("man_made")
        if (highway != "speed_camera" && manMade != "speed_camera") return@forEach
        add(
            SpeedCamera(
                id = "osm_node_$id",
                lat = node.lat,
                lng = node.lng,
                type = cameraTypeFrom(tags = tags, fallbackTags = null),
                directionDeg = directionFrom(tags),
                speedLimitKph = speedLimitFrom(tags),
                country = countryFrom(tags, null),
                source = "overpass",
                confidence = if (highway == "speed_camera") 1f else 0.55f,
            )
        )
    }

    relations.forEach { relation ->
        val relationId = relation.optLong("id")
        val relationTags = relation.optJSONObject("tags") ?: return@forEach
        if (relationTags.optString("type") != "enforcement") return@forEach
        if (!relationTags.optString("enforcement").isCameraEnforcement()) return@forEach

        val members = relation.optJSONArray("members") ?: JSONArray()
        val devices = members.objects()
            .filter { it.optString("role") == "device" }
            .mapNotNull { member -> member.devicePoint(nodes, ways) }
            .ifEmpty { relation.optJSONObject("center")?.toPoint()?.let(::listOf) ?: emptyList() }

        devices.forEachIndexed { index, device ->
            val idSuffix = if (devices.size == 1) "" else "_$index"
            val tags = device.tags
            add(
                SpeedCamera(
                    id = "osm_relation_${relationId}$idSuffix",
                    lat = device.lat,
                    lng = device.lng,
                    type = cameraTypeFrom(tags = relationTags, fallbackTags = tags),
                    directionDeg = directionFrom(tags) ?: directionFrom(relationTags),
                    speedLimitKph = speedLimitFrom(relationTags) ?: speedLimitFrom(tags),
                    country = countryFrom(relationTags, tags),
                    source = "overpass",
                    confidence = 0.95f,
                )
            )
        }
    }

    return byId.values.toList()
}

private data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
) {
    fun tiles(): List<Bounds> {
        val latSpan = (maxLat - minLat).coerceAtLeast(0.0)
        val lngSpan = (maxLng - minLng).coerceAtLeast(0.0)
        var latCount = ceil(latSpan / MAX_TILE_LAT_DEG).toInt().coerceAtLeast(1)
        var lngCount = ceil(lngSpan / MAX_TILE_LNG_DEG).toInt().coerceAtLeast(1)
        while (latCount * lngCount > MAX_TILES_PER_VIEWPORT) {
            if (latCount >= lngCount && latCount > 1) latCount-- else if (lngCount > 1) lngCount-- else break
        }

        val latStep = latSpan / latCount
        val lngStep = lngSpan / lngCount
        return buildList {
            for (latIndex in 0 until latCount) {
                for (lngIndex in 0 until lngCount) {
                    add(
                        Bounds(
                            minLat = minLat + latStep * latIndex,
                            maxLat = if (latIndex == latCount - 1) maxLat else minLat + latStep * (latIndex + 1),
                            minLng = minLng + lngStep * lngIndex,
                            maxLng = if (lngIndex == lngCount - 1) maxLng else minLng + lngStep * (lngIndex + 1),
                        )
                    )
                }
            }
        }
    }

    fun cacheKey(): String = "${minLat.roundKey()}:${maxLat.roundKey()}:${minLng.roundKey()}:${maxLng.roundKey()}"

    fun logString(): String = "[${minLat.roundKey()},${minLng.roundKey()} - ${maxLat.roundKey()},${maxLng.roundKey()}]"

    companion object {
        fun from(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): Bounds {
            val sortedMinLat = minOf(minLat, maxLat).coerceIn(-85.0, 85.0)
            val sortedMaxLat = maxOf(minLat, maxLat).coerceIn(-85.0, 85.0)
            val sortedMinLng = minOf(minLng, maxLng).coerceIn(-180.0, 180.0)
            val sortedMaxLng = maxOf(minLng, maxLng).coerceIn(-180.0, 180.0)
            return Bounds(sortedMinLat, sortedMaxLat, sortedMinLng, sortedMaxLng)
        }
    }
}

private data class OsmNode(
    val lat: Double,
    val lng: Double,
    val tags: JSONObject?,
)

private data class OsmWay(
    val nodeIds: List<Long>,
    val center: OsmPoint?,
    val tags: JSONObject?,
) {
    fun point(nodes: Map<Long, OsmNode>): OsmPoint? {
        center?.let { return it.copy(tags = tags) }
        val points = nodeIds.mapNotNull { nodes[it] }
        if (points.isEmpty()) return null
        return OsmPoint(
            lat = points.sumOf { it.lat } / points.size,
            lng = points.sumOf { it.lng } / points.size,
            tags = tags,
        )
    }
}

private data class OsmPoint(
    val lat: Double,
    val lng: Double,
    val tags: JSONObject?,
)

private fun JSONObject.devicePoint(
    nodes: Map<Long, OsmNode>,
    ways: Map<Long, OsmWay>,
): OsmPoint? {
    val ref = optLong("ref")
    return when (optString("type")) {
        "node" -> nodes[ref]?.let { OsmPoint(it.lat, it.lng, it.tags) }
        "way" -> ways[ref]?.point(nodes)
        else -> null
    }
}

private fun cameraTypeFrom(tags: JSONObject, fallbackTags: JSONObject?): CameraType {
    val enforcement = tags.optString("enforcement").ifBlank { fallbackTags?.optString("enforcement").orEmpty() }
    val cameraType = tags.optString("camera:type").ifBlank { fallbackTags?.optString("camera:type").orEmpty() }
    val speedCamera = tags.optString("speed_camera").ifBlank { fallbackTags?.optString("speed_camera").orEmpty() }
    val averageSpeed = tags.optString("average_speed").ifBlank { fallbackTags?.optString("average_speed").orEmpty() }
    val freeText = listOf(
        tags.optString("note"),
        tags.optString("description"),
        fallbackTags?.optString("note").orEmpty(),
        fallbackTags?.optString("description").orEmpty(),
    ).joinToString(" ").lowercase()
    val hasSpeed = enforcement.contains("maxspeed")
    val hasRedLight = enforcement.contains("traffic_signals") ||
        enforcement.contains("red_light") ||
        speedCamera.contains("traffic_signals") ||
        speedCamera.contains("red_light") ||
        freeText.contains("rotlicht") ||
        freeText.contains("red light") ||
        freeText.contains("red-light")
    return when {
        enforcement.contains("average_speed") ||
            cameraType == "average" ||
            speedCamera == "average" ||
            averageSpeed == "yes" -> CameraType.SECTION_CONTROL
        hasSpeed && hasRedLight -> CameraType.COMBINED
        hasRedLight -> CameraType.RED_LIGHT
        else -> CameraType.FIXED_SPEED
    }
}

private fun speedLimitFrom(tags: JSONObject?): Int? {
    tags ?: return null
    val direct = parseSpeed(tags.optString("maxspeed"))
    if (direct != null) return direct
    return parseSpeed(tags.optString("maxspeed:conditional").substringBefore("@").trim())
}

private fun parseSpeed(value: String): Int? {
    if (value.isBlank()) return null
    val normalized = value.lowercase().trim()
    if (normalized in setOf("signals", "none", "walk", "variable")) return null
    val number = Regex("""\d+""").find(normalized)?.value?.toIntOrNull() ?: return null
    return if ("mph" in normalized) (number * 1.609344).roundToInt() else number
}

private fun directionFrom(tags: JSONObject?): Float? {
    tags ?: return null
    val value = tags.optString("direction").lowercase().trim()
    if (value.isBlank()) return null
    value.toFloatOrNull()?.let { return ((it % 360f) + 360f) % 360f }
    return when (value) {
        "n", "north" -> 0f
        "e", "east" -> 90f
        "s", "south" -> 180f
        "w", "west" -> 270f
        "ne", "northeast" -> 45f
        "se", "southeast" -> 135f
        "sw", "southwest" -> 225f
        "nw", "northwest" -> 315f
        else -> null
    }
}

private fun countryFrom(primary: JSONObject?, fallback: JSONObject?): String =
    primary?.optString("addr:country").orEmpty()
        .ifBlank { fallback?.optString("addr:country").orEmpty() }

private fun String.isCameraEnforcement(): Boolean =
    contains("maxspeed") || contains("average_speed") || contains("traffic_signals") || contains("red_light")

private fun JSONArray?.longs(): List<Long> {
    this ?: return emptyList()
    return (0 until length()).map { optLong(it) }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.toPoint(): OsmPoint? {
    val lat = optDouble("lat", Double.NaN)
    val lng = optDouble("lon", Double.NaN)
    return if (lat.isNaN() || lng.isNaN()) null else OsmPoint(lat, lng, optJSONObject("tags"))
}

private fun SpeedCamera.rank(): Float =
    confidence + if (speedLimitKph != null) 0.05f else 0f

private fun Double.roundKey(): String = "%.4f".format(this)
