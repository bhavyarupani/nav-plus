package com.navplus.core.regions

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.navplus.core.regions.db.RegionDao
import com.navplus.core.regions.download.RegionDownloadWorker
import com.navplus.core.regions.model.Region
import com.navplus.core.regions.model.RegionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RegionDao,
) {
    val allRegions: Flow<List<Region>> = dao.observeAll()
    val downloadedRegions: Flow<List<Region>> = dao.observeDownloaded()

    suspend fun coversLocation(lat: Double, lng: Double): Boolean =
        dao.findDownloadedCovering(lat, lng) != null

    suspend fun enqueueDownload(regionId: String) {
        dao.setStatus(regionId, RegionStatus.QUEUED)
        val request = OneTimeWorkRequestBuilder<RegionDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(Data.Builder().putString(RegionDownloadWorker.KEY_REGION_ID, regionId).build())
            .addTag("region_download_$regionId")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    suspend fun cancelDownload(regionId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("region_download_$regionId")
        dao.setStatus(regionId, RegionStatus.AVAILABLE)
    }

    suspend fun deleteRegion(regionId: String) {
        cancelDownload(regionId)
        context.filesDir.resolve("regions/$regionId").deleteRecursively()
        dao.delete(regionId)
    }

    suspend fun seedDefaultRegions(regions: List<Region>) {
        dao.insertAll(regions)
        regions.forEach { catalogueRegion ->
            val existing = dao.getById(catalogueRegion.id) ?: return@forEach
            dao.update(
                catalogueRegion.copy(
                    status = existing.status,
                    downloadedAt = existing.downloadedAt,
                )
            )
        }
    }
}
