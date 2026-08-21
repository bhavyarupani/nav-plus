package com.navplus.core.safety

import androidx.room.Database
import androidx.room.RoomDatabase
import com.navplus.core.safety.model.SpeedCamera
import com.navplus.core.safety.model.SpeedCameraFetchTile

@Database(entities = [SpeedCamera::class, SpeedCameraFetchTile::class], version = 2, exportSchema = true)
abstract class SafetyDatabase : RoomDatabase() {
    abstract fun speedCameraDao(): SpeedCameraDao
}
