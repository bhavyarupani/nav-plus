package com.navplus.core.regions.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.navplus.core.regions.model.Region

@Database(entities = [Region::class], version = 1, exportSchema = true)
abstract class RegionDatabase : RoomDatabase() {
    abstract fun regionDao(): RegionDao
}
