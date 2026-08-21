package com.navplus.core.safety.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_camera_fetch_tiles")
data class SpeedCameraFetchTile(
    @PrimaryKey val tileKey: String,
    val lastSuccessMs: Long?,
    val lastFailureMs: Long?,
    val failureCount: Int,
)

