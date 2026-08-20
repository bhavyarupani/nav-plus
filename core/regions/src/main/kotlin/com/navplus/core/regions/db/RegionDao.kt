package com.navplus.core.regions.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.navplus.core.regions.model.Region
import com.navplus.core.regions.model.RegionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RegionDao {

    @Query("SELECT * FROM regions ORDER BY name ASC")
    fun observeAll(): Flow<List<Region>>

    @Query("SELECT * FROM regions WHERE status = 'READY' ORDER BY name ASC")
    fun observeDownloaded(): Flow<List<Region>>

    @Query("SELECT * FROM regions WHERE id = :id")
    suspend fun getById(id: String): Region?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(regions: List<Region>)

    @Update
    suspend fun update(region: Region)

    @Query("UPDATE regions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: RegionStatus)

    @Query("UPDATE regions SET status = :status, downloadedAt = :at WHERE id = :id")
    suspend fun markReady(id: String, status: RegionStatus = RegionStatus.READY, at: Long)

    @Query("DELETE FROM regions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        SELECT * FROM regions
        WHERE status = 'READY'
          AND boundsMinLat <= :lat AND boundsMaxLat >= :lat
          AND boundsMinLng <= :lng AND boundsMaxLng >= :lng
        LIMIT 1
    """)
    suspend fun findDownloadedCovering(lat: Double, lng: Double): Region?
}
