package com.navplus.core.safety

import android.content.Context
import android.util.Log
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeedCameraAssetSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraDao: SpeedCameraDao,
) {
    private val mutex = Mutex()

    suspend fun ensureSeeded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val richSeedCount = cameraDao.countBySource(GERMANY_RICH_SEED_SOURCE)
            val legacySeedCount = cameraDao.countBySource(GERMANY_LEGACY_SEED_SOURCE)
            if (richSeedCount >= GERMANY_RICH_EXPECTED_COUNT && legacySeedCount == 0) return@withContext

            runCatching {
                val json = context.assets.open(GERMANY_RICH_ASSET_PATH).bufferedReader().use { it.readText() }
                val cameras = parseGermanyCameraSeed(json)
                if (cameras.isNotEmpty()) {
                    cameraDao.deleteBySource(GERMANY_LEGACY_SEED_SOURCE)
                    cameraDao.deleteBySource(GERMANY_RICH_SEED_SOURCE)
                    cameras.chunked(UPSERT_BATCH_SIZE).forEach { cameraDao.upsertAll(it) }
                }
                Log.d(TAG, "Seeded ${cameras.size} German speed cameras from asset")
            }.onFailure { error ->
                Log.w(TAG, "German speed camera seed failed: ${error.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SpeedCameraAssetSeeder"
        private const val GERMANY_RICH_ASSET_PATH = "speed_cameras/germany_speed_cameras_master_rich.json"
        private const val GERMANY_RICH_SEED_SOURCE = "seed_speedcams_world_de_master_rich"
        private const val GERMANY_LEGACY_SEED_SOURCE = "seed_speedcams_world_de"
        private const val GERMANY_RICH_EXPECTED_COUNT = 5_148
        private const val UPSERT_BATCH_SIZE = 500
    }
}

internal fun parseGermanyCameraSeed(json: String): List<SpeedCamera> {
    val root = JSONObject(json)
    val cameras = root.optJSONArray("cameras")
    return if (cameras != null) {
        parseGermanyRichCameraSeed(cameras)
    } else {
        parseGermanyGeoJson(root)
    }
}

internal fun parseGermanyGeoJson(json: String): List<SpeedCamera> {
    return parseGermanyGeoJson(JSONObject(json))
}

private fun parseGermanyGeoJson(root: JSONObject): List<SpeedCamera> {
    val features = root.optJSONArray("features") ?: return emptyList()
    val cameras = ArrayList<SpeedCamera>(features.length())
    for (index in 0 until features.length()) {
        val feature = features.optJSONObject(index) ?: continue
        val geometry = feature.optJSONObject("geometry") ?: continue
        if (geometry.optString("type") != "Point") continue
        val coordinates = geometry.optJSONArray("coordinates") ?: continue
        if (coordinates.length() < 2) continue

        val lng = coordinates.optDouble(0, Double.NaN)
        val lat = coordinates.optDouble(1, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) continue

        val properties = feature.optJSONObject("properties") ?: JSONObject()
        val sourceId = properties.optString("id").ifBlank { feature.optString("id").ifBlank { index.toString() } }
        cameras.add(
            SpeedCamera(
                id = "seed_de_$sourceId",
                lat = lat,
                lng = lng,
                type = properties.optString("camera_type").toCameraType(),
                directionDeg = null,
                speedLimitKph = properties.optNullableInt("speed_limit_kmh"),
                country = properties.optString("country_code").ifBlank { "DE" },
                source = "seed_speedcams_world_de",
                confidence = 0.9f,
            )
        )
    }
    return cameras
}

private fun parseGermanyRichCameraSeed(items: org.json.JSONArray): List<SpeedCamera> {
    val cameras = ArrayList<SpeedCamera>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val lat = item.optDouble("latitude", Double.NaN)
        val lng = item.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) continue

        val sourceId = item.optString("id").ifBlank { index.toString() }
        cameras.add(
            SpeedCamera(
                id = "seed_de_master_$sourceId",
                lat = lat,
                lng = lng,
                type = item.toSeedCameraType(),
                directionDeg = item.optNullableFloat("direction_deg"),
                speedLimitKph = item.optNullableInt("speed_limit_kmh"),
                country = item.optString("country_code").ifBlank { "DE" },
                source = "seed_speedcams_world_de_master_rich",
                confidence = item.optConfidence(),
            )
        )
    }
    return cameras
}

private fun JSONObject.toSeedCameraType(): CameraType {
    val cameraType = optString("camera_type")
    val enforcementType = optString("enforcement_type")
    return when {
        cameraType.equals("red_light", ignoreCase = true) ||
            enforcementType.equals("red_light", ignoreCase = true) -> CameraType.RED_LIGHT
        cameraType.equals("combined", ignoreCase = true) ||
            enforcementType.equals("combined", ignoreCase = true) -> CameraType.COMBINED
        cameraType.equals("average", ignoreCase = true) ||
            cameraType.equals("section", ignoreCase = true) ||
            cameraType.equals("section_control", ignoreCase = true) ||
            enforcementType.equals("section_control", ignoreCase = true) -> CameraType.SECTION_CONTROL
        cameraType.equals("mobile", ignoreCase = true) ||
            optBoolean("is_mobile_zone", false) -> CameraType.MOBILE_ZONE
        else -> CameraType.FIXED_SPEED
    }
}

private fun String.toCameraType(): CameraType =
    when (lowercase()) {
        "red_light", "traffic_signals" -> CameraType.RED_LIGHT
        "combined" -> CameraType.COMBINED
        "average", "section", "section_control" -> CameraType.SECTION_CONTROL
        "mobile" -> CameraType.MOBILE_ZONE
        else -> CameraType.FIXED_SPEED
    }

private fun JSONObject.optNullableInt(name: String): Int? =
    if (isNull(name)) null else optInt(name).takeIf { it > 0 }

private fun JSONObject.optNullableFloat(name: String): Float? =
    if (isNull(name)) null else optDouble(name, Double.NaN).takeUnless { it.isNaN() }?.toFloat()

private fun JSONObject.optConfidence(): Float {
    val confidence = optDouble("confidence", Double.NaN)
    return if (confidence.isNaN()) 0.9f else confidence.toFloat().coerceIn(0f, 1f)
}
