package com.navplus.core.safety

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.navplus.core.safety.model.SpeedCameraFetchTile
import com.navplus.core.safety.model.SpeedCamera

@Dao
interface SpeedCameraDao {
    @Query("""
        SELECT * FROM speed_cameras
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getCamerasInBoundingBox(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double,
    ): List<SpeedCamera>

    @Upsert
    suspend fun upsertAll(cameras: List<SpeedCamera>)

    @Query("DELETE FROM speed_cameras WHERE lastUpdatedMs < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long)

    @Query("SELECT COUNT(*) FROM speed_cameras")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM speed_cameras WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM speed_cameras WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT * FROM speed_camera_fetch_tiles WHERE tileKey = :tileKey")
    suspend fun getFetchTile(tileKey: String): SpeedCameraFetchTile?

    @Upsert
    suspend fun upsertFetchTile(tile: SpeedCameraFetchTile)
}
