package com.navplus.core.safety

import android.util.Log
import com.navplus.core.safety.model.CameraType
import com.navplus.core.safety.model.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverpassCameraFetcher @Inject constructor(
    private val cameraDao: SpeedCameraDao,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // Overpass hosts in fallback order. The IP-literal is lambert.openstreetmap.de
    // (65.109.112.52) accessed over HTTP so DNS/TLS issues don't block it.
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "http://65.109.112.52/api/interpreter",         // bypass DNS
        "https://overpass.kumi.systems/api/interpreter",
        "http://overpass-api.de/api/interpreter",
    )
    private val maxAttemptsPerEndpoint = 1

    suspend fun fetchAndCache(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double,
    ) = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:18];node[\"highway\"=\"speed_camera\"]($minLat,$minLng,$maxLat,$maxLng);out body;"
        val body = FormBody.Builder().add("data", query).build()
        for (ep in endpoints) {
            repeat(maxAttemptsPerEndpoint) { attempt ->
                try {
                    Log.d(TAG, "Overpass $ep attempt ${attempt + 1}")
                    val rb = Request.Builder()
                        .url(ep)
                        .post(body)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", "NavPlus/1.0 (Android; +https://navplus.app)")
                    // IP-literal endpoint needs an explicit Host header for virtual hosting
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
                    val cameras = parse(responseBody)
                    Log.d(TAG, "Overpass returned ${cameras.size} cameras")
                    if (cameras.isNotEmpty()) cameraDao.upsertAll(cameras)
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Overpass $ep attempt ${attempt + 1} failed: ${e.message}")
                }
            }
        }
        Log.e(TAG, "Overpass: all endpoints failed")
    }

    private fun parse(json: String): List<SpeedCamera> {
        val elements = JSONObject(json).getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { i ->
            val node = elements.getJSONObject(i)
            val tags = node.optJSONObject("tags") ?: return@mapNotNull null
            val lat = node.optDouble("lat", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            val lng = node.optDouble("lon", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            val maxspeedTag = tags.optString("maxspeed", "")
            val speedLimit = maxspeedTag.removeSuffix(" mph").toIntOrNull()
                ?: maxspeedTag.toIntOrNull()
            val enforcement = tags.optString("enforcement", "")
            val cameraType = when {
                enforcement.contains("traffic_signals") -> CameraType.RED_LIGHT
                tags.optString("camera:type", "") == "average" -> CameraType.AVERAGE_SPEED_START
                tags.optString("average_speed", "") == "yes" -> CameraType.AVERAGE_SPEED_START
                else -> CameraType.FIXED_SPEED
            }
            SpeedCamera(
                id = "osm_${node.getLong("id")}",
                lat = lat,
                lng = lng,
                type = cameraType,
                directionDeg = null,
                speedLimitKph = speedLimit,
                country = tags.optString("addr:country", ""),
                source = "overpass",
            )
        }
    }

    companion object {
        private const val TAG = "OverpassFetcher"
    }
}
