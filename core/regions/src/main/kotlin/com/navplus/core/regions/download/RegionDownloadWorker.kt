package com.navplus.core.regions.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.navplus.core.regions.db.RegionDao
import com.navplus.core.regions.model.RegionStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@HiltWorker
class RegionDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: RegionDao,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val regionId = inputData.getString(KEY_REGION_ID) ?: return Result.failure()
        val region = dao.getById(regionId) ?: return Result.failure()

        dao.setStatus(regionId, RegionStatus.DOWNLOADING)
        return try {
            val regionDir = File(appContext.filesDir, "regions/$regionId")
            regionDir.mkdirs()

            downloadFile(region.mapUrl, File(regionDir, "map.mbtiles"))
            downloadFile(region.routingUrl, File(regionDir, "routing.osm-gh"))
            downloadFile(region.searchUrl, File(regionDir, "search.photon"))

            dao.markReady(regionId, at = System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            dao.setStatus(regionId, RegionStatus.FAILED)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun downloadFile(url: String, dest: File) = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) error("HTTP ${response.code} for $url")
        response.body!!.byteStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {
        const val KEY_REGION_ID = "region_id"
    }
}
