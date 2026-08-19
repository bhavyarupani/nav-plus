package com.navplus.core.safety

import com.navplus.core.safety.model.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeedCameraRepository @Inject constructor(
    private val cameraDao: SpeedCameraDao,
) {
    suspend fun getCamerasNear(lat: Double, lng: Double, radiusMeters: Double): List<SpeedCamera> =
        withContext(Dispatchers.IO) {
            val latDelta = radiusMeters / 111_000.0
            val lngDelta = radiusMeters / (111_000.0 * Math.cos(Math.toRadians(lat)))
            cameraDao.getCamerasInBoundingBox(
                minLat = lat - latDelta, maxLat = lat + latDelta,
                minLng = lng - lngDelta, maxLng = lng + lngDelta,
            )
        }

    suspend fun upsertCameras(cameras: List<SpeedCamera>) =
        withContext(Dispatchers.IO) { cameraDao.upsertAll(cameras) }

    suspend fun deleteOlderThan(timestampMs: Long) =
        withContext(Dispatchers.IO) { cameraDao.deleteOlderThan(timestampMs) }
}
