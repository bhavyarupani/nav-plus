package com.roadpulse.auto.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.roadpulse.auto.R

/**
 * Downloads one region package in the background via [RegionDownloadManager], as a foreground
 * service with a progress notification - a genuinely new pattern for this app (no foreground
 * service or notification exists anywhere else in it), but standard, expected practice for a
 * multi-hundred-MB user-initiated download that must survive the user backgrounding the app.
 * `CameraDataRefreshJobService`'s `JobScheduler`-based periodic-refresh pattern is the wrong
 * precedent here - this is immediate, user-initiated, progress-visible work, not OS-scheduled
 * background maintenance.
 *
 * Only one download runs at a time; a second `start()` while one is already in progress is
 * ignored (the caller should disable its own "Download" affordance while a download is running -
 * see `SettingsActivity`'s offline-regions card).
 */
class RegionDownloadService : Service() {
    @Volatile private var activeRegionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val regionId = intent?.getStringExtra(EXTRA_REGION_ID)
        if (regionId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (activeRegionId != null) {
            Log.w(TAG, "Ignoring download request for $regionId - already downloading $activeRegionId")
            return START_NOT_STICKY
        }
        activeRegionId = regionId
        startForeground(NOTIFICATION_ID, buildNotification(regionId, 0, 0))
        RegionDownloadEvents.publish(RegionDownloadEvents.Event.Started(regionId))
        Thread { runDownload(regionId) }.start()
        return START_NOT_STICKY
    }

    private fun runDownload(regionId: String) {
        val installStore = RegionInstallStore(applicationContext)
        val catalogRepository = RegionCatalogRepository(applicationContext)
        val downloadManager = RegionDownloadManager(installStore)
        val region = catalogRepository.currentCatalog().firstOrNull { it.id == regionId }
        val result =
            if (region == null) {
                RegionDownloadResult.Failed("Region $regionId is not in the current catalog")
            } else {
                downloadManager.download(region) { bytesRead, totalBytes ->
                    updateNotification(regionId, bytesRead, totalBytes)
                    RegionDownloadEvents.publish(RegionDownloadEvents.Event.Progress(regionId, bytesRead, totalBytes))
                }
            }
        RegionDownloadEvents.publish(RegionDownloadEvents.Event.Finished(regionId, result))
        activeRegionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        regionId: String,
        bytesRead: Long,
        totalBytes: Long,
    ): Notification {
        ensureChannel()
        val percent = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_roadpulse)
            .setContentTitle("Downloading $regionId")
            .setContentText(if (totalBytes > 0) "$percent%" else "Starting…")
            .setProgress(100, percent, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(
        regionId: String,
        bytesRead: Long,
        totalBytes: Long,
    ) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(regionId, bytesRead, totalBytes))
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Offline map downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val EXTRA_REGION_ID = "region_id"
        private const val TAG = "RegionDownloadService"
        private const val CHANNEL_ID = "region_downloads"
        private const val NOTIFICATION_ID = 0x5244

        fun start(
            context: Context,
            regionId: String,
        ) {
            val intent = Intent(context, RegionDownloadService::class.java).putExtra(EXTRA_REGION_ID, regionId)
            context.startForegroundService(intent)
        }
    }
}
