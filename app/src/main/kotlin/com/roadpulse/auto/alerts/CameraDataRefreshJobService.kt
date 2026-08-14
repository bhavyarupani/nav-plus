package com.roadpulse.auto.alerts

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class CameraDataRefreshJobService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val succeeded =
                runCatching {
                    val coordinator = CameraDataRefreshCoordinator(this)
                    if (!coordinator.isRefreshDue()) {
                        true
                    } else {
                        coordinator.refresh().failureCount == 0
                    }
                }.getOrDefault(false)
            if (!stopped) jobFinished(params, !succeeded)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }
}

object CameraDataRefreshScheduler {
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job =
            JobInfo
                .Builder(
                    JOB_ID,
                    ComponentName(context, CameraDataRefreshJobService::class.java),
                ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(REFRESH_INTERVAL_MILLIS)
                .build()
        scheduler.schedule(job)
    }

    private const val JOB_ID = 0x524F4144
    private const val REFRESH_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
}
