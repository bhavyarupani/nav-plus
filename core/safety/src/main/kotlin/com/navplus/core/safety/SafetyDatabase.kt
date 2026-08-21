package com.navplus.core.safety

import androidx.room.Database
import androidx.room.RoomDatabase
import com.navplus.core.safety.model.SpeedCamera

@Database(entities = [SpeedCamera::class], version = 1, exportSchema = true)
abstract class SafetyDatabase : RoomDatabase() {
    abstract fun speedCameraDao(): SpeedCameraDao
}
